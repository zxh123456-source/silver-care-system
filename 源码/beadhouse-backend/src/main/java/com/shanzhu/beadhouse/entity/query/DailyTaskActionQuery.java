package com.shanzhu.beadhouse.entity.query;
import lombok.Data;
@Data
public class DailyTaskActionQuery {
    private Long id;
    private Integer revision;
    private Long targetStaffId;
    private String note;
}
