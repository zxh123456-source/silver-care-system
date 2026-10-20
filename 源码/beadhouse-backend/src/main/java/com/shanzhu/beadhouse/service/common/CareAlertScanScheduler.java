package com.shanzhu.beadhouse.service.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Slf4j
@Component
@ConditionalOnProperty(name="ai.alert.scan.enabled",havingValue="true",matchIfMissing=true)
public class CareAlertScanScheduler {
    @Resource private CareAlertScanWorker worker;
    @Scheduled(initialDelayString="${ai.alert.scan.initial-delay-ms:10000}",fixedDelayString="${ai.alert.scan.delay-ms:60000}")
    public void run() {
        try { worker.tick(); }
        catch(RuntimeException failure) { log.warn("后台护理告警核对暂未完成，将自动重试"); }
    }
}
