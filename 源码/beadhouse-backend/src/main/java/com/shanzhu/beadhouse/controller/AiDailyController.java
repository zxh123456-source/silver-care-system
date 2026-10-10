package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.service.AiDailyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Date;

@Api(tags = "每日护理助手")
@RestController
@RequestMapping("/ai/daily")
@PreAuthorize("@AuthorityAssert.hasAuthority('/ai/daily/index')")
public class AiDailyController {
    @Resource
    private AiDailyService dailyService;
    @Resource
    private com.shanzhu.beadhouse.service.common.DailyTaskService taskService;

    @PostMapping("/tasks/sync")
    public Result syncTasks(@RequestParam(required=false) @DateTimeFormat(pattern="yyyy-MM-dd") Date date,
                            @RequestHeader String token) { return taskService.sync(date); }

    @GetMapping("/tasks")
    public Result tasks(@RequestParam(required=false) @DateTimeFormat(pattern="yyyy-MM-dd") Date date,
                        @RequestParam(defaultValue="ACTIVE") String state,
                        @RequestHeader String token) { return taskService.list(date,state); }

    @GetMapping("/tasks/owners")
    public Result owners(@RequestParam Long id,@RequestHeader String token) { return taskService.owners(id); }

    @PostMapping("/tasks/claim")
    public Result claim(@RequestBody com.shanzhu.beadhouse.entity.query.DailyTaskActionQuery query,
                        @RequestHeader String token) { return taskService.act("claim",query); }

    @PostMapping("/tasks/transfer")
    public Result transfer(@RequestBody com.shanzhu.beadhouse.entity.query.DailyTaskActionQuery query,
                           @RequestHeader String token) { return taskService.act("transfer",query); }

    @PostMapping("/tasks/complete")
    public Result complete(@RequestBody com.shanzhu.beadhouse.entity.query.DailyTaskActionQuery query,
                           @RequestHeader String token) { return taskService.act("complete",query); }

    @GetMapping("/overview")
    @ApiOperation(value = "生成只读每日护理汇总", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result overview(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") Date date,
                           @RequestHeader String token) {
        return dailyService.overview(date);
    }
}
