package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.MedicationExecutionQuery;
import com.shanzhu.beadhouse.entity.query.MedicationPlanQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.service.AiMedicationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Date;

@Api(tags = "用药执行核对")
@RestController
@RequestMapping("/ai/medication")
@PreAuthorize("@AuthorityAssert.hasAuthority('/ai/medication/index')")
public class AiMedicationController {
    @Resource
    private AiMedicationService medicationService;

    @PostMapping("/plan")
    @ApiOperation(value = "新增用药执行计划", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result addPlan(@RequestBody MedicationPlanQuery query, @RequestHeader String token) {
        return medicationService.addPlan(query);
    }

    @GetMapping("/day")
    @ApiOperation(value = "查询当日用药执行清单", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result day(@RequestParam Long elderId,
                      @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") Date date,
                      @RequestHeader String token) {
        return medicationService.day(elderId, date);
    }

    @PostMapping("/execute")
    @ApiOperation(value = "登记用药执行结果", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result execute(@RequestBody MedicationExecutionQuery query, @RequestHeader String token) {
        return medicationService.execute(query);
    }

    @PutMapping("/plan/disable")
    @ApiOperation(value = "停用用药执行计划", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result disablePlan(@RequestParam Long planId, @RequestHeader String token) {
        return medicationService.disablePlan(planId);
    }

    @GetMapping("/elders")
    public Result elders(PageSearchElderByKeyQuery query, @RequestHeader String token) {
        return medicationService.pageElders(query);
    }
}
