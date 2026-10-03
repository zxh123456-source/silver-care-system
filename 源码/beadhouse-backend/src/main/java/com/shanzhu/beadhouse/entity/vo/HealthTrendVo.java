package com.shanzhu.beadhouse.entity.vo;

import com.shanzhu.beadhouse.entity.po.HealthData;
import lombok.Data;

import java.util.List;

@Data
public class HealthTrendVo {
    private Long elderId;
    private String elderName;
    private Integer recordCount;
    private String summary;
    private Boolean modelUsed;
    private List<String> changeReminders;
    private List<HealthData> records;
}
