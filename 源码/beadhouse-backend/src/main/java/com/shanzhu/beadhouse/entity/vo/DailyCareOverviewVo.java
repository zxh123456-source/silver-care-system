package com.shanzhu.beadhouse.entity.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class DailyCareOverviewVo {
    private String date;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date generatedAt;
    private Boolean readOnly;
    private Boolean modelUsed;
    private String statusBasis;
    private String notice;
    private Integer todayCareRecordCount;
    private Integer carePendingCount;
    private Integer medicationDoneCount;
    private Integer medicationPendingCount;
    private Integer medicationSkippedCount;
    private Integer healthReviewCount;
    private Integer serviceBacklogCount;
    private Integer attentionCount;
    private Boolean truncated;
    private List<DailyCareItemVo> items;
}
