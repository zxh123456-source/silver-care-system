package com.shanzhu.beadhouse.entity.query;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("制度检索请求")
public class PolicyQuery {
    @ApiModelProperty(value = "问题", required = true, example = "老人跌倒后应该先做什么")
    private String question;
}
