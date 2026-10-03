package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.common.constant.CheckEnum;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.dao.mapper.MedicationExecutionMapper;
import com.shanzhu.beadhouse.dao.mapper.MedicationPlanMapper;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.Elder;
import com.shanzhu.beadhouse.entity.po.MedicationExecution;
import com.shanzhu.beadhouse.entity.po.MedicationPlan;
import com.shanzhu.beadhouse.entity.query.MedicationExecutionQuery;
import com.shanzhu.beadhouse.entity.query.MedicationPlanQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.MedicationDayVo;
import com.shanzhu.beadhouse.entity.vo.MedicationTaskVo;
import com.shanzhu.beadhouse.service.AiMedicationService;
import com.shanzhu.beadhouse.service.common.AiAuditRecorder;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

@Service
public class AiMedicationServiceImpl implements AiMedicationService {
    private static final Set<String> ALLOWED_PERIODS = new HashSet<>(Arrays.asList("早", "中", "晚", "睡前"));
    private static final Set<String> ALLOWED_STATUS = new HashSet<>(Arrays.asList("DONE", "SKIPPED"));

    @Resource
    private MedicationPlanMapper planMapper;
    @Resource
    private MedicationExecutionMapper executionMapper;
    @Resource
    private ElderMapper elderMapper;
    @Resource
    private AiAuditRecorder auditRecorder;
    @Resource
    private AuthorityAssert authorityAssert;
    @Resource
    private AiDataScopeService dataScopeService;

    @Override
    public Result addPlan(MedicationPlanQuery query) {
        if (query != null && query.getElderId() != null) dataScopeService.assertElderAccess(query.getElderId());
        String error = validatePlan(query);
        if (error != null) return Result.error(400, error);
        MedicationPlan plan = new MedicationPlan();
        List<String> uniquePeriods = new ArrayList<>(new LinkedHashSet<>(query.getPeriods()));
        plan.setElderId(query.getElderId());
        plan.setMedicineName(query.getMedicineName().trim());
        plan.setDoseInstruction(query.getDoseInstruction().trim());
        plan.setPeriods(String.join(",", uniquePeriods));
        plan.setStartDate(normalizeDate(query.getStartDate()));
        plan.setEndDate(query.getEndDate() == null ? null : normalizeDate(query.getEndDate()));
        plan.setEnabled(YesNoEnum.YES.getCode());
        planMapper.insert(plan);
        auditRecorder.record("用药执行", "新增用药计划", "medication_plan", plan.getId(),
                "时段数=" + uniquePeriods.size());
        return Result.success(plan);
    }

    @Override
    public Result day(Long elderId, Date date) {
        if (elderId == null) return Result.error(400, "请选择老人");
        dataScopeService.assertElderAccess(elderId);
        Elder elder = elderMapper.selectById(elderId);
        if (elder == null) return Result.error(400, "老人档案不存在");
        if (!isActiveResident(elder)) return Result.error(400, "老人当前不在入住或退住审核状态");
        Date day = normalizeDate(date == null ? new Date() : date);
        List<MedicationPlan> plans = planMapper.selectList(new QueryWrapper<MedicationPlan>()
                .eq("elder_id", elderId)
                .le("start_date", day)
                .and(wrapper -> wrapper.isNull("end_date").or().ge("end_date", day))
                .orderByAsc("medicine_name", "id"));
        List<MedicationExecution> executions = executionMapper.selectList(new QueryWrapper<MedicationExecution>()
                .eq("elder_id", elderId)
                .eq("execution_date", day));
        Map<String, MedicationExecution> executionMap = new HashMap<>();
        for (MedicationExecution execution : executions) {
            executionMap.put(key(execution.getPlanId(), execution.getPeriod()), execution);
        }
        List<MedicationTaskVo> tasks = new ArrayList<>();
        int done = 0;
        int skipped = 0;
        for (MedicationPlan plan : plans) {
            for (String period : plan.getPeriods().split(",")) {
                MedicationExecution execution = executionMap.get(key(plan.getId(), period));
                MedicationTaskVo task = new MedicationTaskVo();
                task.setPlanId(plan.getId());
                task.setElderId(elderId);
                task.setElderName(elder.getName());
                task.setMedicineName(plan.getMedicineName());
                task.setDoseInstruction(plan.getDoseInstruction());
                task.setPeriod(period);
                task.setStatus(execution == null ? "PENDING" : execution.getStatus());
                task.setExecutedTime(execution == null ? null : execution.getExecutedTime());
                task.setNote(execution == null ? null : execution.getNote());
                if ("DONE".equals(task.getStatus())) done++;
                if ("SKIPPED".equals(task.getStatus())) skipped++;
                tasks.add(task);
            }
        }
        MedicationDayVo result = new MedicationDayVo();
        result.setElderId(elderId);
        result.setElderName(elder.getName());
        result.setDate(new SimpleDateFormat("yyyy-MM-dd").format(day));
        result.setTotal(tasks.size());
        result.setDone(done);
        result.setSkipped(skipped);
        result.setPending(tasks.size() - done - skipped);
        result.setTasks(tasks);
        return Result.success(result);
    }

    @Override
    @Transactional
    public Result execute(MedicationExecutionQuery query) {
        if (query == null || query.getPlanId() == null || query.getExecutionDate() == null || blank(query.getPeriod())) {
            return Result.error(400, "计划、日期和用药时段不能为空");
        }
        if (!ALLOWED_STATUS.contains(query.getStatus())) return Result.error(400, "执行状态不合法");
        if ("SKIPPED".equals(query.getStatus()) && blank(query.getNote())) return Result.error(400, "未执行时必须填写原因");
        MedicationPlan plan = planMapper.selectById(query.getPlanId());
        if (plan == null) return Result.error(400, "用药计划不存在");
        dataScopeService.assertElderAccess(plan.getElderId());
        Elder elder = elderMapper.selectById(plan.getElderId());
        if (elder == null || !isActiveResident(elder)) return Result.error(400, "老人当前不在入住或退住审核状态");
        if (!Arrays.asList(plan.getPeriods().split(",")).contains(query.getPeriod())) return Result.error(400, "该计划不包含此用药时段");
        Date day = normalizeDate(query.getExecutionDate());
        if (day.before(plan.getStartDate()) || (plan.getEndDate() != null && day.after(plan.getEndDate()))) {
            return Result.error(400, "执行日期不在计划有效期内");
        }
        MedicationExecution execution = new MedicationExecution();
        execution.setPlanId(plan.getId());
        execution.setElderId(plan.getElderId());
        execution.setExecutionDate(day);
        execution.setPeriod(query.getPeriod());
        execution.setStatus(query.getStatus());
        execution.setExecutedTime(new Date());
        execution.setNote(query.getNote());
        Long operatorId = authorityAssert.getLoginUserInfo() == null ? 1L : authorityAssert.getLoginUserId();
        executionMapper.upsertExecution(execution, operatorId);
        auditRecorder.record("用药执行", "登记用药执行", "medication_execution", execution.getId(),
                "状态=" + query.getStatus());
        return Result.success(execution);
    }

    @Override
    public Result disablePlan(Long planId) {
        MedicationPlan plan = planMapper.selectById(planId);
        if (plan == null) return Result.error(400, "用药计划不存在");
        dataScopeService.assertElderAccess(plan.getElderId());
        if (YesNoEnum.NO.getCode().equals(plan.getEnabled())) return Result.success(plan);
        Date today = normalizeDate(new Date());
        plan.setEnabled(YesNoEnum.NO.getCode());
        if (plan.getEndDate() == null || plan.getEndDate().after(today)) plan.setEndDate(today);
        planMapper.updateById(plan);
        auditRecorder.record("用药执行", "停用用药计划", "medication_plan", planId, null);
        return Result.success(plan);
    }

    @Override
    public Result pageElders(PageSearchElderByKeyQuery query) {
        return dataScopeService.pageAccessibleElders(query);
    }

    private String validatePlan(MedicationPlanQuery query) {
        if (query == null || query.getElderId() == null) return "请选择老人";
        Elder elder = elderMapper.selectById(query.getElderId());
        if (elder == null) return "老人档案不存在";
        if (!isActiveResident(elder)) return "老人当前不在入住或退住审核状态";
        if (blank(query.getMedicineName()) || query.getMedicineName().trim().length() > 100) return "药品名称不能为空且不能超过100字";
        if (blank(query.getDoseInstruction()) || query.getDoseInstruction().trim().length() > 200) return "执行说明不能为空且不能超过200字";
        if (query.getPeriods() == null || query.getPeriods().isEmpty()) return "至少选择一个用药时段";
        if (!ALLOWED_PERIODS.containsAll(query.getPeriods())) return "用药时段不合法";
        if (query.getStartDate() == null) return "开始日期不能为空";
        if (query.getEndDate() != null && normalizeDate(query.getEndDate()).before(normalizeDate(query.getStartDate()))) return "结束日期不能早于开始日期";
        return null;
    }

    private Date normalizeDate(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private String key(Long planId, String period) {
        return planId + "#" + period;
    }

    private boolean isActiveResident(Elder elder) {
        return Objects.equals(elder.getCheckFlag(), CheckEnum.ENTER.getStatus())
                || Objects.equals(elder.getCheckFlag(), CheckEnum.EXIT_AUDIT.getStatus());
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
