package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.service.common.AiAuditRecorder;
import com.shanzhu.beadhouse.service.AiMetricsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@Api(tags = "AI 操作审计")
@RestController
@RequestMapping("/ai/audit")
@PreAuthorize("@AuthorityAssert.hasAuthority('/ai/audit/index')")
public class AiAuditController {
    @Resource
    private AiAuditRecorder auditRecorder;
    @Resource
    private AiMetricsService metricsService;

    @GetMapping("/page")
    @ApiOperation(value = "分页查询 AI 操作", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result page(@RequestParam(defaultValue = "1") Integer pageNum,
                       @RequestParam(defaultValue = "20") Integer pageSize,
                       @RequestHeader String token) {
        return Result.success(auditRecorder.page(pageNum, pageSize));
    }

    @GetMapping("/metrics/summary")
    @ApiOperation(value = "查询 AI 指标汇总", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result metrics(@RequestParam(defaultValue = "7") Integer days, @RequestHeader String token) {
        return metricsService.summary(days);
    }
}
