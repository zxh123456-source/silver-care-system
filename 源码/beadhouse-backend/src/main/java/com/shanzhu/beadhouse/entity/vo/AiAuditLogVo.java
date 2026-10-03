package com.shanzhu.beadhouse.entity.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

@Data
public class AiAuditLogVo {
    private Long id;
    private String module;
    private String action;
    private String objectType;
    private Long objectId;
    private String operatorName;
    private String detail;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
