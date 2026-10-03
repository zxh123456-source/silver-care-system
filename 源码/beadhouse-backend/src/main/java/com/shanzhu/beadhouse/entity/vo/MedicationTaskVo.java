package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

import java.util.Date;

@Data
public class MedicationTaskVo {
    private Long planId;
    private Long elderId;
    private String elderName;
    private String medicineName;
    private String doseInstruction;
    private String period;
    private String status;
    private Date executedTime;
    private String note;
}
