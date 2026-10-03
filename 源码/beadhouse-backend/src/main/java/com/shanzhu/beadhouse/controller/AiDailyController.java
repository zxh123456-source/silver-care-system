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

    @GetMapping("/overview")
    @ApiOperation(value = "生成只读每日护理汇总", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result overview(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") Date date,
                           @RequestHeader String token) {
        return dailyService.overview(date);
    }
}
