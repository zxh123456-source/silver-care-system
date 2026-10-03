package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AiMetricsSummaryVo {
    private Integer days;
    private Integer pipelineCalls;
    private Double successRate;
    private Double fallbackRate;
    private Double errorRate;
    private Double policyGroundedRate;
    private Double modelUseRate;
    private Long p95LatencyMs;
    private Integer ragPending;
    private Integer ragFailed;
    private List<Map<String, Object>> daily;
    private Map<String, Integer> byFeature;
    private Map<String, Integer> retrievalBackend;
}
