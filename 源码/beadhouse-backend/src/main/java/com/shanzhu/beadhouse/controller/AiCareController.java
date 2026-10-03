package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.AiCareNoteDraftQuery;
import com.shanzhu.beadhouse.entity.query.SaveCareNoteQuery;
import com.shanzhu.beadhouse.entity.query.FamilyReportQuery;
import com.shanzhu.beadhouse.service.AiCareService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Date;

@Api(tags = "AI 护理工作台")
@RestController
@RequestMapping("/ai/care")
@PreAuthorize("@AuthorityAssert.hasAuthority('/ai/care/index')")
public class AiCareController {
    @Resource
    private AiCareService aiCareService;

    @PostMapping("/draft")
    @ApiOperation(value = "生成护理记录草稿", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result draft(@RequestBody AiCareNoteDraftQuery query, @RequestHeader String token) {
        return aiCareService.draft(query);
    }

    @PostMapping("/save")
    @ApiOperation(value = "确认并保存护理记录", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result save(@RequestBody SaveCareNoteQuery query, @RequestHeader String token) {
        return aiCareService.save(query);
    }

    @PutMapping("/update")
    @ApiOperation(value = "修改护理记录", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result update(@RequestBody SaveCareNoteQuery query, @RequestHeader String token) {
        return aiCareService.update(query);
    }

    @GetMapping("/handover")
    @ApiOperation(value = "生成交班摘要", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result handover(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") Date date,
                           @RequestHeader String token) {
        return aiCareService.handover(date);
    }

    @PutMapping("/complete")
    @ApiOperation(value = "完成护理待跟进事项", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result complete(@RequestParam Long id, @RequestHeader String token) {
        return aiCareService.complete(id);
    }

    @PostMapping("/family-report")
    @ApiOperation(value = "生成家属周报草稿", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result familyReport(@RequestBody FamilyReportQuery query, @RequestHeader String token) {
        return aiCareService.familyReport(query);
    }
}
