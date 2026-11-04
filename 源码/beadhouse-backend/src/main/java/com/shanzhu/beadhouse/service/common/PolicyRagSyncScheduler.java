package com.shanzhu.beadhouse.service.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Slf4j
@Component
@ConditionalOnProperty(name="ai.rag.retry.enabled",havingValue="true",matchIfMissing=true)
public class PolicyRagSyncScheduler {
    @Resource private PolicyRagSyncService worker;
    @Scheduled(fixedDelayString="${ai.rag.retry-interval-ms:30000}")
    public void run() {
        try { worker.retryPending(); }
        catch(RuntimeException failure) { log.warn("制度索引后台同步暂未完成，将自动重试"); }
    }
}
