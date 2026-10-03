package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.PageDepositRechargeByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.query.RechargeQuery;
import com.shanzhu.beadhouse.service.DepositRechargeService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@Api(tags = "预存充值")
@RestController
@RequestMapping("/depositRecharge")
@PreAuthorize("@AuthorityAssert.hasAuthority('/fee/pay')")
public class DepositRechargeController {
    @Resource
    private DepositRechargeService depositRechargeService;

    @GetMapping("/pageDepositRechargeByKey")
    @ApiOperation(value = "分页查询预存充值", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result pageDepositRechargeByKey(@ApiParam(value = "分页查询预存充值请求实体", required = true) PageDepositRechargeByKeyQuery pageDepositRechargeByKeyQuery,
                                           @ApiParam(value = "接口访问请求头", required = true) @RequestHeader String token) {
        return depositRechargeService.pageDepositRechargeByKey(pageDepositRechargeByKeyQuery);
    }

    @GetMapping("/pageSearchElderByKey")
    @ApiOperation(value = "分页搜索老人", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result pageSearchElderByKey(@ApiParam(value = "分页搜索老人请求实体", required = true) PageSearchElderByKeyQuery pageSearchElderByKeyQuery,
                                       @ApiParam(value = "接口访问请求头", required = true) @RequestHeader String token) {
        return depositRechargeService.pageSearchElderByKey(pageSearchElderByKeyQuery);
    }

    @PutMapping("/recharge")
    @ApiOperation(value = "入住老人账户充值", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result recharge(@ApiParam(value = "入住老人账户充值请求实体", required = true) @RequestBody RechargeQuery rechargeQuery,
                           @ApiParam(value = "接口访问请求头", required = true) @RequestHeader String token) {
        return depositRechargeService.recharge(rechargeQuery);
    }
}
