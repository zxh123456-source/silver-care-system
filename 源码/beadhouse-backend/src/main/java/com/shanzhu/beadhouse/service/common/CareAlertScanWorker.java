package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.dao.mapper.CareAlertScanMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.dao.mapper.MedicationPlanMapper;
import com.shanzhu.beadhouse.entity.po.CareAlertScanCheckpoint;
import com.shanzhu.beadhouse.entity.po.Elder;
import com.shanzhu.beadhouse.entity.po.MedicationPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import javax.annotation.PostConstruct;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Internal worker: never impersonates a user and never changes the HTTP data-scope policy. */
@Service
public class CareAlertScanWorker {
    @Resource private CareAlertScanMapper mapper;
    @Resource private MedicationPlanMapper planMapper;
    @Resource private ElderMapper elderMapper;
    @Resource private CareAlertService evidence;
    @Resource private PlatformTransactionManager transactionManager;
    @Value("${ai.alert.scan.batch-size:100}") private int batchSize=100;
    @Value("${ai.alert.scan.max-batches:4}") private int maxBatches=4;
    @Value("${ai.alert.scan.lease-seconds:120}") private int leaseSeconds=120;
    @Value("${ai.alert.scan.rescan-seconds:900}") private int rescanSeconds=900;
    private final String owner=UUID.randomUUID().toString();
    private Clock clock=Clock.systemUTC();
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");

    @PostConstruct public void validateSettings() {
        if(batchSize<1 || batchSize>100 || maxBatches<1 || maxBatches>32 || leaseSeconds<30 || leaseSeconds>3600 || rescanSeconds<1 || rescanSeconds>86400)
            throw new IllegalArgumentException("告警扫描参数超出允许范围");
    }
    private TransactionTemplate transaction() {
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(leaseSeconds);
        return tx;
    }
    public Long claimLease() {
        LocalDate today=LocalDate.now(clock.withZone(SHANGHAI));
        return transaction().execute(status -> {
            mapper.initialize(Date.valueOf(today.minusDays(31)),Date.valueOf(today.minusDays(1)));
            CareAlertScanCheckpoint cp=mapper.lock();
            if(mapper.claimable()!=1) return null;
            Date start=cp.getScanDate();
            long cursor=cp.getPlanCursor();
            Date target=cp.getTargetDate();
            if(start.after(target)) {
                // Catch up every missed day first; otherwise periodically revisit the recent window.
                LocalDate earliest=today.minusDays(31);
                if(cp.getLastCompletedDate()!=null && cp.getLastCompletedDate().toLocalDate().plusDays(1).isBefore(earliest))
                    earliest=cp.getLastCompletedDate().toLocalDate().plusDays(1);
                start=Date.valueOf(earliest);
                cursor=0;
                target=Date.valueOf(today.minusDays(1));
            }
            // Persisted state may outlive a clock correction; unfinished older dates remain intact.
            Date yesterday=Date.valueOf(today.minusDays(1));
            if(target.after(yesterday)) target=yesterday;
            mapper.claim(owner,leaseSeconds,start,target,cursor);
            return cp.getLeaseToken()+1;
        });
    }
    /** -1 means the target is complete; zero still advances an empty calendar day. */
    public int processBatch(Long token) {
        if(token==null) throw new IllegalArgumentException("扫描租约不能为空");
        return transaction().execute(status -> {
            CareAlertScanCheckpoint cp=mapper.lock();
            requireLease(token);
            if(cp.getScanDate().after(cp.getTargetDate())
                || !cp.getScanDate().toLocalDate().isBefore(LocalDate.now(clock.withZone(SHANGHAI)))) return -1;
            List<MedicationPlan> plans=mapper.plans(cp.getScanDate(),cp.getPlanCursor(),batchSize);
            for(MedicationPlan candidate:plans) {
                // Residence changes and checkout cannot race a newly generated automatic alert.
                Elder elder=elderMapper.selectByIdForUpdate(candidate.getElderId());
                MedicationPlan plan=planMapper.lockById(candidate.getId());
                if(elder!=null && ("入住".equals(elder.getCheckFlag()) || "退住审核".equals(elder.getCheckFlag()))
                    && plan!=null && !plan.getStartDate().after(cp.getScanDate())
                    && (plan.getEndDate()==null || !plan.getEndDate().before(cp.getScanDate())))
                    evidence.scanPlanEvidence(plan,cp.getScanDate());
                cp.setPlanCursor(candidate.getId());
            }
            if(plans.size()<batchSize) {
                if(cp.getLastCompletedDate()==null || cp.getScanDate().after(cp.getLastCompletedDate()))
                    cp.setLastCompletedDate(cp.getScanDate());
                cp.setScanDate(Date.valueOf(cp.getScanDate().toLocalDate().plusDays(1)));
                cp.setPlanCursor(0L);
            }
            // The row lock prevents a replacement worker until commit; expiry must also roll back work.
            requireLease(token);
            mapper.advance(cp);
            return plans.size();
        });
    }
    private void requireLease(Long token) {
        if(mapper.valid(owner,token)!=1) throw new IllegalStateException("扫描租约已失效");
    }
    public int tick() {
        Long token=claimLease();
        if(token==null) return 0;
        int processed=0;
        try {
            for(int batch=0;batch<maxBatches;batch++) {
                int count=processBatch(token);
                if(count<0) break;
                processed+=count;
            }
            transaction().execute(status -> {
                CareAlertScanCheckpoint cp=mapper.lock();
                requireLease(token);
                return mapper.release(owner,token,cp.getScanDate().after(cp.getTargetDate()) ? rescanSeconds : 0);
            });
            return processed;
        } catch(RuntimeException failure) {
            // New transaction records only a safe operational message. Failed batch/cursor rolled back.
            transaction().execute(status -> mapper.failed(owner,token));
            throw failure;
        }
    }
}
