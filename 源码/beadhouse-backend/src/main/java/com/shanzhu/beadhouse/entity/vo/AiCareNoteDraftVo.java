package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

@Data
public class AiCareNoteDraftVo {
    private Long elderId;
    private String sourceText;
    private String observation;
    private String actionTaken;
    private String followUp;
    private String appetite;
    private String sleep;
    private String medicine;
    private String activity;
    private boolean modelUsed;
    private String warning;
}
