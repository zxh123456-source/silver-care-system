package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

import java.util.List;

@Data
public class PolicyAnswerVo {
    private String question;
    private String answer;
    private Boolean grounded;
    private Boolean modelUsed;
    private String retrievalBackend;
    private List<PolicyCitationVo> citations;
}
