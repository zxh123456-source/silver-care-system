package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.HealthMeasurementQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.service.AiHealthService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import javax.annotation.Resource;

@Api(tags = "AI 健康趋势")
@RestController
@RequestMapping("/ai/health")
@PreAuthorize("@AuthorityAssert.hasAuthority('/ai/health/index')")
public class AiHealthController {
    @Resource
    private AiHealthService aiHealthService;

    @PostMapping("/measurement")
    @ApiOperation(value = "新增日常健康测量", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result addMeasurement(@RequestBody HealthMeasurementQuery query, @RequestHeader String token) {
        return aiHealthService.addMeasurement(query);
    }

    @GetMapping("/trend")
    @ApiOperation(value = "查询老人健康趋势", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result trend(@RequestParam Long elderId,
                        @RequestParam(defaultValue = "30") Integer limit,
                        @RequestHeader String token) {
        return aiHealthService.trend(elderId, limit);
    }

    @GetMapping("/elders")
    @ApiOperation(value = "分页搜索在住老人", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result elders(PageSearchElderByKeyQuery query, @RequestHeader String token) {
        return aiHealthService.pageElders(query);
    }
}
