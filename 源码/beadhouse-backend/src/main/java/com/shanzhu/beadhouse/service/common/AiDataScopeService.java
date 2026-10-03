package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.common.constant.CheckEnum;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderStaffAssignmentMapper;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.base.DropDown;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.Elder;
import com.shanzhu.beadhouse.entity.po.ElderStaffAssignment;
import com.shanzhu.beadhouse.entity.po.Staff;
import com.shanzhu.beadhouse.entity.query.ElderStaffAssignmentQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.ElderStaffAssignmentVo;
import com.shanzhu.beadhouse.entity.vo.LoginUserVo;
import com.shanzhu.beadhouse.entity.vo.PageSearchElderByKeyVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AiDataScopeService {
    @Resource
    private AuthorityAssert authorityAssert;
    @Resource
    private ElderStaffAssignmentMapper assignmentMapper;
    @Resource
    private ElderMapper elderMapper;
    @Resource
    private StaffMapper staffMapper;
    @Resource
    private CommonFunc commonFunc;
    @Resource
    private PageUtil pageUtil;
    @Resource
    private AiAuditRecorder auditRecorder;
    @Value("${ai.data-scope.admin-role-id:1}")
    private long adminRoleId;

    public boolean isAdmin() {
        LoginUserVo user = authorityAssert.getLoginUserInfo();
        if (user == null) return false;
        if (user.getRoleId() != null) return user.getRoleId() == adminRoleId;
        Staff staff = staffMapper.selectById(user.getId());
        return staff != null && staff.getRoleId() != null && staff.getRoleId() == adminRoleId;
    }

    public void assertElderAccess(Long elderId) {
        if (elderId == null) throw new BusinessRuntimeException(400, "老人编号不能为空");
        if (isAdmin()) return;
        Long staffId = authorityAssert.getLoginUserId();
        Long count = assignmentMapper.selectCount(new QueryWrapper<ElderStaffAssignment>()
                .eq("elder_id", elderId).eq("staff_id", staffId).eq("active", YesNoEnum.YES.getCode()));
        if (count == null || count == 0) throw new BusinessRuntimeException(403, "无权访问该老人数据");
    }

    public Set<Long> allowedElderIds(Collection<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) return Collections.emptySet();
        Set<Long> candidates = new HashSet<>(candidateIds);
        if (isAdmin()) return candidates;
        List<ElderStaffAssignment> assignments = assignmentMapper.selectList(new QueryWrapper<ElderStaffAssignment>()
                .eq("staff_id", authorityAssert.getLoginUserId())
                .eq("active", YesNoEnum.YES.getCode())
                .in("elder_id", candidates));
        return assignments.stream().map(ElderStaffAssignment::getElderId).collect(Collectors.toSet());
    }

    public Set<Long> currentAssignedElderIds() {
        if (isAdmin()) return Collections.emptySet();
        return assignmentMapper.selectList(new QueryWrapper<ElderStaffAssignment>()
                .eq("staff_id", authorityAssert.getLoginUserId()).eq("active", YesNoEnum.YES.getCode()))
                .stream().map(ElderStaffAssignment::getElderId).collect(Collectors.toSet());
    }

    public <T> void applyElderScope(QueryWrapper<T> query, String elderColumn) {
        if (isAdmin()) return;
        Set<Long> assigned = currentAssignedElderIds();
        if (assigned.isEmpty()) query.eq(elderColumn, -1L);
        else query.in(elderColumn, assigned);
    }

    public Result pageAccessibleElders(PageSearchElderByKeyQuery query) {
        if (query.getPageNum() == null || query.getPageNum() < 1) query.setPageNum(1);
        if (query.getPageSize() == null || query.getPageSize() < 1) query.setPageSize(10);
        query.setPageSize(Math.min(query.getPageSize(), 100));
        List<PageSearchElderByKeyVo> elders = commonFunc.listPageElderByKey(query,
                Arrays.asList(CheckEnum.ENTER.getStatus(), CheckEnum.EXIT_AUDIT.getStatus()));
        if (!isAdmin()) {
            Set<Long> allowed = allowedElderIds(elders.stream().map(PageSearchElderByKeyVo::getId).collect(Collectors.toList()));
            elders = elders.stream().filter(item -> allowed.contains(item.getId())).collect(Collectors.toList());
        }
        PageResult<PageSearchElderByKeyVo> result = pageUtil.packPageResultData(elders, query.getPageNum(), query.getPageSize());
        return Result.success(result);
    }

    public Result assignments() {
        List<ElderStaffAssignment> assignments = assignmentMapper.selectList(new QueryWrapper<ElderStaffAssignment>()
                .eq("active", YesNoEnum.YES.getCode()).orderByDesc("update_time", "id"));
        Set<Long> elderIds = assignments.stream().map(ElderStaffAssignment::getElderId).collect(Collectors.toSet());
        Set<Long> staffIds = assignments.stream().map(ElderStaffAssignment::getStaffId).collect(Collectors.toSet());
        Map<Long, Elder> elders = elderIds.isEmpty() ? new HashMap<>() : elderMapper.selectBatchIds(elderIds).stream()
                .collect(Collectors.toMap(Elder::getId, item -> item));
        Map<Long, Staff> staff = staffIds.isEmpty() ? new HashMap<>() : staffMapper.selectBatchIds(staffIds).stream()
                .collect(Collectors.toMap(Staff::getId, item -> item));
        List<ElderStaffAssignmentVo> result = new ArrayList<>();
        for (ElderStaffAssignment item : assignments) {
            ElderStaffAssignmentVo vo = new ElderStaffAssignmentVo();
            vo.setId(item.getId());
            vo.setElderId(item.getElderId());
            vo.setStaffId(item.getStaffId());
            vo.setActive(item.getActive());
            vo.setElderName(elders.containsKey(item.getElderId()) ? elders.get(item.getElderId()).getName() : "已删除老人");
            vo.setStaffName(staff.containsKey(item.getStaffId()) ? staff.get(item.getStaffId()).getName() : "已删除员工");
            result.add(vo);
        }
        return Result.success(result);
    }

    public Result staffOptions() {
        List<Staff> staff = staffMapper.selectList(new QueryWrapper<Staff>()
                .eq("leave_flag", YesNoEnum.NO.getCode()).orderByAsc("name", "id"));
        List<DropDown> options = new ArrayList<>();
        for (Staff item : staff) {
            DropDown option = new DropDown();
            option.setId(item.getId());
            option.setName(item.getName());
            options.add(option);
        }
        return Result.success(options);
    }

    public Result assign(ElderStaffAssignmentQuery query) {
        if (query == null || query.getElderId() == null || query.getStaffId() == null) {
            return Result.error(400, "老人和员工不能为空");
        }
        if (elderMapper.selectById(query.getElderId()) == null || staffMapper.selectById(query.getStaffId()) == null) {
            return Result.error(400, "老人或员工不存在");
        }
        Long operatorId = authorityAssert.getLoginUserId();
        assignmentMapper.upsertAssignment(query.getElderId(), query.getStaffId(), operatorId);
        auditRecorder.record("数据权限", "分配老人", "elder_staff_assignment", null, null);
        return Result.success();
    }

    public Result revoke(Long elderId, Long staffId) {
        ElderStaffAssignment assignment = assignmentMapper.selectOne(new QueryWrapper<ElderStaffAssignment>()
                .eq("elder_id", elderId).eq("staff_id", staffId));
        if (assignment == null) return Result.success();
        assignment.setActive(YesNoEnum.NO.getCode());
        assignmentMapper.updateById(assignment);
        auditRecorder.record("数据权限", "撤销老人分配", "elder_staff_assignment", assignment.getId(), null);
        return Result.success();
    }
}
