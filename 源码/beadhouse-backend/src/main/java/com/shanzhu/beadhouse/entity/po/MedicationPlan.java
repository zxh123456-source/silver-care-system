package com.shanzhu.beadhouse.entity.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Data
@TableName("medication_plan")
@EqualsAndHashCode(callSuper = true)
public class MedicationPlan extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long elderId;
    private String medicineName;
    private String doseInstruction;
    private String periods;
    private Date startDate;
    private Date endDate;
    private String enabled;
}
