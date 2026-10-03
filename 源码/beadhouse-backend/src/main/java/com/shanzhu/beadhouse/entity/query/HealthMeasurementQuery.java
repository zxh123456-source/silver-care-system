package com.shanzhu.beadhouse.entity.query;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Date;

@Data
@ApiModel("日常健康测量请求")
public class HealthMeasurementQuery {
    @ApiModelProperty(value = "老人编号", required = true, example = "1")
    private Long elderId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date measureTime;
    private Double weight;
    private Double temperature;
    private Integer heartRate;
    private Integer systolicBloodPressure;
    private Integer diastolicBloodPressure;
    private Integer bloodOxygenSaturation;
    private Double fastingBloodGlucose;
    private Double postprandialBloodGlucose;
    private String remarks;
}
