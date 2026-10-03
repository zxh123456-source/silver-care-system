package com.shanzhu.beadhouse.entity.query;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Date;

@Data
@ApiModel("确认护理记录请求")
public class SaveCareNoteQuery {
    private Long id;
    @ApiModelProperty(value = "老人编号", required = true, example = "1")
    private Long elderId;
    private Long staffId;
    private Date eventTime;
    private String sourceText;
    private String observation;
    private String actionTaken;
    private String followUp;
    private String appetite;
    private String sleep;
    private String medicine;
    private String activity;
}
