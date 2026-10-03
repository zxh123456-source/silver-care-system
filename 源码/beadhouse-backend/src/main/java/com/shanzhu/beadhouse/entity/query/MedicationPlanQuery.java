package com.shanzhu.beadhouse.entity.query;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class MedicationPlanQuery {
    private Long elderId;
    private String medicineName;
    private String doseInstruction;
    private List<String> periods;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
}
