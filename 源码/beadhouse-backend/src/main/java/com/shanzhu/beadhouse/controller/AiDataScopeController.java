package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.ElderStaffAssignmentQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai/scope")
public class AiDataScopeController {
    @Resource
    private AiDataScopeService dataScopeService;

    @GetMapping("/elders")
    @PreAuthorize("@AuthorityAssert.hasAnyAuthority('/ai/care/index','/ai/health/index','/ai/medication/index','/ai/daily/index')")
    public Result elders(PageSearchElderByKeyQuery query, @RequestHeader String token) {
        return dataScopeService.pageAccessibleElders(query);
    }

    @GetMapping("/assignments")
    @PreAuthorize("@AuthorityAssert.hasAuthority('/ai/scope/index')")
    public Result assignments(@RequestHeader String token) {
        return dataScopeService.assignments();
    }

    @GetMapping("/staff-options")
    @PreAuthorize("@AuthorityAssert.hasAuthority('/ai/scope/index')")
    public Result staffOptions(@RequestHeader String token) {
        return dataScopeService.staffOptions();
    }

    @PostMapping("/assignment")
    @PreAuthorize("@AuthorityAssert.hasAuthority('/ai/scope/index')")
    public Result assign(@RequestBody ElderStaffAssignmentQuery query, @RequestHeader String token) {
        return dataScopeService.assign(query);
    }

    @DeleteMapping("/assignment")
    @PreAuthorize("@AuthorityAssert.hasAuthority('/ai/scope/index')")
    public Result revoke(@RequestParam Long elderId, @RequestParam Long staffId, @RequestHeader String token) {
        return dataScopeService.revoke(elderId, staffId);
    }
}
