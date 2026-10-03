package com.shanzhu.beadhouse.entity.query;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

@Data
public class MedicationExecutionQuery {
    private Long planId;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date executionDate;
    private String period;
    private String status;
    private String note;
}
