package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.dao.mapper.PolicyDocMapper;
import com.shanzhu.beadhouse.dao.mapper.PolicyRagSyncMapper;
import com.shanzhu.beadhouse.entity.po.PolicyDoc;
import com.shanzhu.beadhouse.entity.po.PolicyRagSync;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PolicyRagSyncService {
    @Resource
    private PolicyRagSyncMapper syncMapper;
    @Resource
    private PolicyDocMapper policyDocMapper;
    @Resource
    private PolicyRagClient ragClient;
    @Resource
    private AiAuditRecorder auditRecorder;
    @Resource
    private AiMetricRecorder metricRecorder;

    public String enqueueUpsert(String title, String source, List<PolicyDoc> docs) {
        if (!ragClient.isEnabled()) return "DISABLED";
        String documentKey = documentKey(title, source);
        String revision = revision(docs);
        syncMapper.upsertDesired(documentKey, title, source, "UPSERT", revision);
        afterCommit(() -> syncKey(documentKey));
        return documentKey;
    }

    public String enqueueDelete(String title, String source) {
        if (!ragClient.isEnabled()) return "DISABLED";
        String documentKey = documentKey(title, source);
        syncMapper.upsertDesired(documentKey, title, source, "DELETE", null);
        afterCommit(() -> syncKey(documentKey));
        return documentKey;
    }

    public int reindexAll() {
        if (!ragClient.isEnabled()) return 0;
        List<PolicyDoc> docs = policyDocMapper.selectList(new QueryWrapper<PolicyDoc>().orderByAsc("title", "source", "section_no"));
        Map<String, List<PolicyDoc>> grouped = new LinkedHashMap<>();
        for (PolicyDoc doc : docs) {
            grouped.computeIfAbsent(doc.getTitle() + "\u0000" + doc.getSource(), key -> new ArrayList<>()).add(doc);
        }
        for (List<PolicyDoc> documentDocs : grouped.values()) {
            PolicyDoc first = documentDocs.get(0);
            enqueueUpsert(first.getTitle(), first.getSource(), documentDocs);
        }
        return grouped.size();
    }

    @Scheduled(fixedDelayString = "${ai.rag.retry-interval-ms:30000}")
    public void retryPending() {
        if (!ragClient.isEnabled()) return;
        for (PolicyRagSync item : syncMapper.listDue(20)) {
            sync(item);
        }
    }

    public void syncKey(String documentKey) {
        PolicyRagSync item = syncMapper.selectOne(new QueryWrapper<PolicyRagSync>().eq("document_key", documentKey));
        if (item != null && !"SYNCED".equals(item.getStatus())) sync(item);
    }

    public PolicyRagSync find(String documentKey) {
        return syncMapper.selectOne(new QueryWrapper<PolicyRagSync>().eq("document_key", documentKey));
    }

    private void sync(PolicyRagSync item) {
        long startedAt = System.currentTimeMillis();
        try {
            if ("DELETE".equals(item.getDesiredAction())) {
                ragClient.deleteDocument(item.getDocumentKey());
            } else {
                List<PolicyDoc> docs = policyDocMapper.selectList(new QueryWrapper<PolicyDoc>()
                        .eq("title", item.getTitle()).eq("source", item.getSource()).orderByAsc("section_no", "id"));
                if (docs.isEmpty()) throw new IllegalStateException("missing source document");
                ragClient.indexDocument(item.getDocumentKey(), item.getTitle(), item.getSource(), docs);
            }
            syncMapper.markSynced(item.getDocumentKey(), item.getDesiredAction(), item.getRevision());
            auditRecorder.record("制度知识库", "同步混合检索索引", "policy_rag_sync", item.getId(),
                    "操作=" + item.getDesiredAction() + "，结果=SYNCED，文档键=" + shortKey(item.getDocumentKey()));
            metricRecorder.record(AiMetricRecorder.Feature.POLICY_SYNC, AiMetricRecorder.Stage.SYNC,
                    AiMetricRecorder.Outcome.SUCCESS, item.getDesiredAction(), false, null,
                    System.currentTimeMillis() - startedAt, 1, 1, null, null);
        } catch (Exception exception) {
            String errorCode = exception.getClass().getSimpleName();
            syncMapper.markFailed(item.getDocumentKey(), item.getDesiredAction(), item.getRevision(), errorCode);
            log.warn("Policy RAG sync failed: key={}, action={}, exception={}",
                    shortKey(item.getDocumentKey()), item.getDesiredAction(), errorCode);
            auditRecorder.record("制度知识库", "同步混合检索索引", "policy_rag_sync", item.getId(),
                    "操作=" + item.getDesiredAction() + "，结果=FAILED，错误=" + errorCode + "，文档键=" + shortKey(item.getDocumentKey()));
            metricRecorder.record(AiMetricRecorder.Feature.POLICY_SYNC, AiMetricRecorder.Stage.SYNC,
                    AiMetricRecorder.Outcome.ERROR, item.getDesiredAction(), false, null,
                    System.currentTimeMillis() - startedAt, 1, 0, null, errorCode);
        }
    }

    public String documentKey(String title, String source) {
        return sha256(title.trim() + "\u0000" + source.trim());
    }

    private String revision(List<PolicyDoc> docs) {
        List<PolicyDoc> ordered = new ArrayList<>(docs);
        ordered.sort(Comparator.comparing(PolicyDoc::getSectionNo).thenComparing(PolicyDoc::getId));
        StringBuilder value = new StringBuilder();
        for (PolicyDoc doc : ordered) {
            value.append(doc.getId()).append('\u0000').append(doc.getSectionNo()).append('\u0000')
                    .append(doc.getContent()).append('\u0001');
        }
        return sha256(value.toString());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String shortKey(String value) {
        return value == null || value.length() <= 12 ? value : value.substring(0, 12);
    }
}
