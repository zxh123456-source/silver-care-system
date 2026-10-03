package com.shanzhu.beadhouse.entity.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("ai_audit_log")
@EqualsAndHashCode(callSuper = true)
public class AiAuditLog extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private String module;
    private String action;
    private String objectType;
    private Long objectId;
    private Long operatorId;
    private String operatorName;
    private String detail;
}
