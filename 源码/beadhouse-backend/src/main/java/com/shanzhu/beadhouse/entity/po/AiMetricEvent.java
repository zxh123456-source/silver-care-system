package com.shanzhu.beadhouse.entity.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("ai_metric_event")
@EqualsAndHashCode(callSuper = true)
public class AiMetricEvent extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private String feature;
    private String stage;
    private String outcome;
    private String backend;
    private String modelUsed;
    private String grounded;
    private Long durationMs;
    private Integer inputCount;
    private Integer outputCount;
    private Integer promptTokens;
    private Integer completionTokens;
    private String fallbackCode;
    private String errorCode;
}
