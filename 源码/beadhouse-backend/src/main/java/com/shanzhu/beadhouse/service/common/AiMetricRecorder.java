package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.dao.mapper.AiMetricEventMapper;
import com.shanzhu.beadhouse.entity.po.AiMetricEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class AiMetricRecorder {
    public enum Feature { CARE_DRAFT, CARE_HANDOVER, CARE_FAMILY, HEALTH_TREND, POLICY_QUERY, POLICY_SYNC, DAILY_OVERVIEW }
    public enum Stage { PIPELINE, RETRIEVAL, MODEL, SYNC }
    public enum Outcome { SUCCESS, FALLBACK, NO_HIT, ERROR, DISABLED }

    @Resource
    private AiMetricEventMapper metricMapper;

    public void record(Feature feature, Stage stage, Outcome outcome, String backend,
                       boolean modelUsed, Boolean grounded, long durationMs,
                       int inputCount, int outputCount, String fallbackCode, String errorCode) {
        try {
            AiMetricEvent event = new AiMetricEvent();
            event.setFeature(feature.name());
            event.setStage(stage.name());
            event.setOutcome(outcome.name());
            event.setBackend(safeCode(backend));
            event.setModelUsed(modelUsed ? "Y" : "N");
            event.setGrounded(grounded == null ? null : grounded ? "Y" : "N");
            event.setDurationMs(Math.max(0, durationMs));
            event.setInputCount(Math.max(0, inputCount));
            event.setOutputCount(Math.max(0, outputCount));
            event.setPromptTokens(null);
            event.setCompletionTokens(null);
            event.setFallbackCode(safeCode(fallbackCode));
            event.setErrorCode(safeCode(errorCode));
            metricMapper.insert(event);
        } catch (Exception exception) {
            log.warn("AI metric write failed: feature={}, stage={}, exception={}",
                    feature, stage, exception.getClass().getSimpleName());
        }
    }

    private String safeCode(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim().toUpperCase().replaceAll("[^A-Z0-9_.-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 80));
    }
}
