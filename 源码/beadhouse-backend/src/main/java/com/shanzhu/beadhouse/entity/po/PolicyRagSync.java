package com.shanzhu.beadhouse.entity.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Data
@TableName("policy_rag_sync")
@EqualsAndHashCode(callSuper = true)
public class PolicyRagSync extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private String documentKey;
    private String title;
    private String source;
    private String desiredAction;
    private String revision;
    private String status;
    private Integer attemptCount;
    private Date nextRetryTime;
    private String lastErrorCode;
}
