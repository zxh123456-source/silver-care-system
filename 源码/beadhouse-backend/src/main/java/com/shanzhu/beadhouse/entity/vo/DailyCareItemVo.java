package com.shanzhu.beadhouse.entity.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

@Data
public class DailyCareItemVo {
    private String key;
    private String category;
    private String level;
    private Long sourceId;
    private Long elderId;
    private String elderName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date eventTime;
    private String title;
    private String detail;
    private String status;
    private String targetPath;
    private Boolean requiresManualConfirmation;
    private String confirmationHint;
}
