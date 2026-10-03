package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

import java.util.List;

@Data
public class PolicyRagSearchVo {
    private String backend;
    private Boolean degraded;
    private String fallbackCode;
    private String embeddingBackend;
    private List<PolicyRagHitVo> hits;
}
