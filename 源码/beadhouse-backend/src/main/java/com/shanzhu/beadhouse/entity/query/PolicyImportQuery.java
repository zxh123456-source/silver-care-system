package com.shanzhu.beadhouse.entity.query;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("制度文档导入请求")
public class PolicyImportQuery {
    @ApiModelProperty(value = "制度标题", required = true, example = "老人跌倒处理流程")
    private String title;
    @ApiModelProperty(value = "文档来源", required = true, example = "护理部制度汇编 2026")
    private String source;
    @ApiModelProperty(value = "制度正文", required = true)
    private String content;
}
