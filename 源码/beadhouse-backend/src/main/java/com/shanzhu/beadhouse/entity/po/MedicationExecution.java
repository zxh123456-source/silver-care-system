package com.shanzhu.beadhouse.entity.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Data
@TableName("medication_execution")
@EqualsAndHashCode(callSuper = true)
public class MedicationExecution extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long planId;
    private Long elderId;
    private Date executionDate;
    private String period;
    private String status;
    private Date executedTime;
    private String note;
}
