package com.shanzhu.beadhouse.entity.query;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("AI 护理记录草稿请求")
public class AiCareNoteDraftQuery {
    @ApiModelProperty(value = "老人编号", required = true, example = "1")
    private Long elderId;
    @ApiModelProperty(value = "护理员的原始口述", required = true, example = "下午没有参加活动，说昨晚没睡好，已经跟晚班说了，明天再问一下")
    private String sourceText;
}
