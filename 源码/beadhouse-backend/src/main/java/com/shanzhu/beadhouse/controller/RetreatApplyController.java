package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.GetReserveByReserveIdAndElderIdQuery;
import com.shanzhu.beadhouse.entity.query.PageRetreatApplyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.service.RetreatApplyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@Api(tags = "退住申请")
@RestController
@RequestMapping("/retreatApply")
@PreAuthorize("@AuthorityAssert.hasAuthority('/check-in/check-out')")
public class RetreatApplyController {
    @Resource
    private RetreatApplyService retreatApplyService;

    @GetMapping("/pageRetreatApplyByKey")
    @ApiOperation(value = "分页查询退住申请", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result pageRetreatApplyByKey(@ApiParam(value = "分页查询退住申请请求实体", required = true) PageRetreatApplyQuery pageRetreatApplyQuery,
                                        @ApiParam(value = "接口访问请求头", required = true) @RequestHeader String token) {
        return retreatApplyService.pageRetreatApplyByKey(pageRetreatApplyQuery);
    }

    @GetMapping("/pageSearchElderByKey")
    @ApiOperation(value = "分页搜索老人", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result pageSearchElderByKey(@ApiParam(value = "分页搜索老人请求实体", required = true) PageSearchElderByKeyQuery pageSearchElderByKeyQuery,
                                       @ApiParam(value = "接口访问请求头", required = true) @RequestHeader String token) {
        return retreatApplyService.pageSearchElderByKey(pageSearchElderByKeyQuery);
    }

    @PostMapping("/addRetreatApply")
    @ApiOperation(value = "新增退住申请", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result addRetreatApply(@ApiParam(value = "新增退住申请请求参数", required = true) @RequestBody GetReserveByReserveIdAndElderIdQuery getReserveByReserveIdAndElderIdQuery,
                                  @ApiParam(value = "接口访问请求头", required = true) @RequestHeader String token) {
        return retreatApplyService.addRetreatApply(getReserveByReserveIdAndElderIdQuery.getElderId());
    }
}
