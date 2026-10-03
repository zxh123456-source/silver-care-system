package com.shanzhu.beadhouse.entity.vo;

import com.shanzhu.beadhouse.entity.query.OperateCheckContractQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@ApiModel(value = "分页搜索紧急联系人响应实体")
public class PageSearchEmergencyContactVo extends OperateCheckContractQuery.OperateEmergencyContactQuery {
    @ApiModelProperty(value = "序号", example = "1")
    private Long rank;
}
