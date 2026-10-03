package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

import java.util.List;

@Data
public class MedicationDayVo {
    private Long elderId;
    private String elderName;
    private String date;
    private Integer total;
    private Integer done;
    private Integer skipped;
    private Integer pending;
    private List<MedicationTaskVo> tasks;
}
