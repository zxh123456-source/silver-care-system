package com.shanzhu.beadhouse.entity.query;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

@Data
@ApiModel("家属周报请求")
public class FamilyReportQuery {
    @ApiModelProperty(value = "老人编号", required = true, example = "1")
    private Long elderId;
    @ApiModelProperty(value = "开始日期", required = true, example = "2026-09-21")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    @ApiModelProperty(value = "结束日期", required = true, example = "2026-09-27")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
}
