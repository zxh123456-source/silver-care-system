package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.dao.mapper.PolicyDocMapper;
import com.shanzhu.beadhouse.dao.mapper.PolicyRagSyncMapper;
import com.shanzhu.beadhouse.entity.po.PolicyDoc;
import com.shanzhu.beadhouse.entity.po.PolicyRagSync;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import javax.annotation.PostConstruct;
import java.util.*;

/** MySQL owns document generations; workers publish only the immutable claimed request. */
@Slf4j
@Component
public class PolicyRagSyncService {
    @Resource private PolicyRagSyncMapper syncMapper;
    @Resource private PolicyDocMapper policyDocMapper;
    @Resource private PolicyRagClient ragClient;
    @Resource private AuthorityAssert authority;
    @Resource private AiMetricRecorder metricRecorder;
    @Resource private PlatformTransactionManager transactionManager;
    @Value("${ai.rag.lease-seconds:30}") private int leaseSeconds=30;
    private final String owner=UUID.randomUUID().toString();

    @PostConstruct public void validateSettings() {
        if(leaseSeconds<1 || leaseSeconds>3600) throw new IllegalArgumentException("制度同步租约须为1至3600秒");
    }
    private TransactionTemplate transaction() {
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(30);
        return tx;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public PolicyRagSync lockDocument(String title,String source) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("制度来源修改必须在事务中执行");
        String key=documentKey(title,source);
        syncMapper.initialize(key,title.trim(),source.trim(),actor());
        return syncMapper.lockDocument(key);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public String enqueueUpsert(String title,String source,List<PolicyDoc> docs) {
        PolicyRagSync row=lockDocument(title,source);
        stage(row,"UPSERT",docs);
        return row.getDocumentKey();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public String enqueueDelete(String title,String source) {
        PolicyRagSync row=lockDocument(title,source);
        stage(row,"DELETE",Collections.emptyList());
        return row.getDocumentKey();
    }
    private void stage(PolicyRagSync row,String action,List<PolicyDoc> docs) {
        long previous=row.getDesiredVersion();
        long next=Math.addExact(previous,1L);
        String payload=PolicyRagPayload.snapshot(row.getDocumentKey(),next,action,row.getTitle(),row.getSource(),docs);
        JsonNode snapshot=PolicyRagPayload.parse(payload);
        row.setDesiredVersion(next); row.setDesiredAction(action); row.setPayloadJson(payload);
        row.setRevision(snapshot.path("payload_hash").asText());
        if(syncMapper.stage(row,previous,actor())!=1) throw new IllegalStateException("制度版本已变化，请重新提交");
    }
    private Long actor() { return authority.getLoginUserInfo()==null ? 0L : authority.getLoginUserId(); }
    public QueryWrapper<PolicyDoc> exactSource(String title,String source) {
        // Parameters remain bound. The comparison matches the byte-sensitive document key.
        return new QueryWrapper<PolicyDoc>().apply("BINARY title = BINARY {0}",title.trim())
            .apply("BINARY source = BINARY {0}",source.trim());
    }
    private List<PolicyDoc> currentDocs(PolicyRagSync row) {
        return policyDocMapper.selectList(exactSource(row.getTitle(),row.getSource()).orderByAsc("section_no","id"));
    }
    public int reindexAll() {
        Map<String,String[]> identities=new LinkedHashMap<>();
        for(PolicyDoc doc:policyDocMapper.selectList(new QueryWrapper<PolicyDoc>().select("title","source").orderByAsc("id")))
            identities.put(documentKey(doc.getTitle(),doc.getSource()),new String[]{doc.getTitle(),doc.getSource()});
        // Include durable tombstones when rebuilding a fresh remote namespace.
        for(PolicyRagSync row:syncMapper.selectList(new QueryWrapper<PolicyRagSync>().orderByAsc("id")))
            identities.put(row.getDocumentKey(),new String[]{row.getTitle(),row.getSource()});
        for(String[] identity:identities.values()) transaction().execute(status -> {
            PolicyRagSync row=lockDocument(identity[0],identity[1]);
            List<PolicyDoc> docs=currentDocs(row);
            stage(row,docs.isEmpty()?"DELETE":"UPSERT",docs);
            return null;
        });
        return identities.size();
    }
    public PolicyRagSync claimOne(String documentKey) {
        if(!ragClient.isEnabled()) return null;
        return transaction().execute(status -> {
            PolicyRagSync row=syncMapper.lockDue(documentKey);
            if(row==null) return null;
            if(row.getDesiredVersion()==0 || row.getPayloadJson()==null) {
                // Migrate unversioned work from the current source under the same serialization lock.
                List<PolicyDoc> docs=currentDocs(row);
                stage(row,docs.isEmpty()?"DELETE":"UPSERT",docs);
            }
            if(syncMapper.claim(row.getId(),owner,leaseSeconds)!=1) return null;
            return syncMapper.lockDocument(row.getDocumentKey());
        });
    }
    public boolean processClaim(PolicyRagSync claim) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("制度同步网络调用不能在来源事务中执行");
        if(claim==null || !owner.equals(claim.getLeaseOwner()) || !ragClient.isEnabled()) return false;
        if(!Boolean.TRUE.equals(transaction().execute(status -> syncMapper.validClaim(claim)==1))) return false;
        long startedAt=System.currentTimeMillis();
        try {
            ragClient.syncDocument(claim);
            boolean completed=Boolean.TRUE.equals(transaction().execute(status -> {
                // NOW() is fixed at statement start: acquire the row lock before starting the expiry CAS.
                if(syncMapper.lockDocument(claim.getDocumentKey())==null) return false;
                if(syncMapper.markSynced(claim)!=1) return false;
                syncMapper.systemAudit(claim.getId(),"操作="+claim.getDesiredAction()+"，结果=SYNCED，版本="+claim.getDesiredVersion());
                return true;
            }));
            if(completed) metricRecorder.record(AiMetricRecorder.Feature.POLICY_SYNC,AiMetricRecorder.Stage.SYNC,
                AiMetricRecorder.Outcome.SUCCESS,claim.getDesiredAction(),false,null,
                System.currentTimeMillis()-startedAt,1,1,null,null);
            return completed;
        } catch(Exception failure) {
            String error=failure.getClass().getSimpleName();
            boolean failed=Boolean.TRUE.equals(transaction().execute(status -> {
                if(syncMapper.lockDocument(claim.getDocumentKey())==null) return false;
                if(syncMapper.markFailed(claim,error)!=1) return false;
                syncMapper.systemAudit(claim.getId(),"操作="+claim.getDesiredAction()+"，结果=FAILED，错误="+error+"，版本="+claim.getDesiredVersion());
                return true;
            }));
            if(failed) {
                log.warn("Policy RAG sync failed: action={}, exception={}",claim.getDesiredAction(),error);
                metricRecorder.record(AiMetricRecorder.Feature.POLICY_SYNC,AiMetricRecorder.Stage.SYNC,
                    AiMetricRecorder.Outcome.ERROR,claim.getDesiredAction(),false,null,
                    System.currentTimeMillis()-startedAt,1,0,null,error);
            }
            return false;
        }
    }
    public int drain(int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("每轮制度同步须为1至100项");
        int completed=0;
        for(int i=0;i<limit;i++) {
            PolicyRagSync row=claimOne(null);
            if(row==null) break;
            if(processClaim(row)) completed++;
        }
        return completed;
    }
    public void retryPending() { if(ragClient.isEnabled()) drain(20); }
    public void syncKey(String key) { PolicyRagSync row=claimOne(key); if(row!=null) processClaim(row); }
    public PolicyRagSync find(String key) { return syncMapper.selectOne(new QueryWrapper<PolicyRagSync>().eq("document_key",key)); }
    public String documentKey(String title,String source) { return PolicyRagPayload.sha256(title.trim()+"\u0000"+source.trim()); }
}
