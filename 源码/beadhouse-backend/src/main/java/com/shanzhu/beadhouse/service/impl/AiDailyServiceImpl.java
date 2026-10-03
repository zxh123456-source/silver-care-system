package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.dao.mapper.CareNoteMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.dao.mapper.HealthDataMapper;
import com.shanzhu.beadhouse.dao.mapper.MedicationExecutionMapper;
import com.shanzhu.beadhouse.dao.mapper.MedicationPlanMapper;
import com.shanzhu.beadhouse.dao.mapper.NurseReserveMapper;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.CareNote;
import com.shanzhu.beadhouse.entity.po.Elder;
import com.shanzhu.beadhouse.entity.po.HealthData;
import com.shanzhu.beadhouse.entity.po.MedicationExecution;
import com.shanzhu.beadhouse.entity.po.MedicationPlan;
import com.shanzhu.beadhouse.entity.po.NurseReserve;
import com.shanzhu.beadhouse.entity.vo.DailyCareItemVo;
import com.shanzhu.beadhouse.entity.vo.DailyCareOverviewVo;
import com.shanzhu.beadhouse.service.AiDailyService;
import com.shanzhu.beadhouse.service.common.AiAuditRecorder;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
import com.shanzhu.beadhouse.service.common.HealthChangeDetector;
import com.shanzhu.beadhouse.service.common.AiMetricRecorder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiDailyServiceImpl implements AiDailyService {
    private static final int MAX_ITEMS = 200;

    @Resource private CareNoteMapper careNoteMapper;
    @Resource private MedicationPlanMapper medicationPlanMapper;
    @Resource private MedicationExecutionMapper medicationExecutionMapper;
    @Resource private HealthDataMapper healthDataMapper;
    @Resource private NurseReserveMapper nurseReserveMapper;
    @Resource private ElderMapper elderMapper;
    @Resource private AiDataScopeService dataScopeService;
    @Resource private HealthChangeDetector healthChangeDetector;
    @Resource private AiAuditRecorder auditRecorder;
    @Resource private AiMetricRecorder metricRecorder;

    @Override
    public Result overview(Date requestedDate) {
        long startedAt = System.currentTimeMillis();
        Date day = normalize(requestedDate == null ? new Date() : requestedDate);
        Date today = normalize(new Date());
        if (day.after(today)) return Result.error(400, "不能生成未来日期的护理汇总");
        Calendar endCalendar = Calendar.getInstance();
        endCalendar.setTime(day);
        endCalendar.add(Calendar.DATE, 1);
        Date dayEnd = endCalendar.getTime();
        List<DailyCareItemVo> items = new ArrayList<>();

        QueryWrapper<CareNote> todayCareQuery = new QueryWrapper<CareNote>()
                .ge("event_time", day).lt("event_time", dayEnd);
        dataScopeService.applyElderScope(todayCareQuery, "elder_id");
        int todayCareCount = careNoteMapper.selectCount(todayCareQuery).intValue();

        QueryWrapper<CareNote> pendingCareQuery = new QueryWrapper<CareNote>()
                .lt("event_time", dayEnd).ne("status", "DONE")
                .isNotNull("follow_up").ne("follow_up", "").orderByAsc("event_time", "id");
        dataScopeService.applyElderScope(pendingCareQuery, "elder_id");
        List<CareNote> pendingCare = careNoteMapper.selectList(pendingCareQuery);
        for (CareNote note : pendingCare) {
            boolean historical = note.getEventTime() != null && note.getEventTime().before(day);
            items.add(item("care-" + note.getId(), "CARE_FOLLOW_UP", historical ? "REVIEW" : "NORMAL",
                    note.getId(), note.getElderId(), note.getEventTime(), historical ? "历史护理待跟进" : "当日护理待跟进",
                    note.getFollowUp(), "PENDING", "/ai-care/workbench", "前往护理工作台核对并由工作人员确认完成"));
        }

        QueryWrapper<MedicationPlan> planQuery = new QueryWrapper<MedicationPlan>()
                .le("start_date", day).and(wrapper -> wrapper.isNull("end_date").or().ge("end_date", day));
        dataScopeService.applyElderScope(planQuery, "elder_id");
        List<MedicationPlan> plans = medicationPlanMapper.selectList(planQuery);
        List<Long> planIds = plans.stream().map(MedicationPlan::getId).collect(Collectors.toList());
        Map<String, MedicationExecution> executionMap = new HashMap<>();
        if (!planIds.isEmpty()) {
            List<MedicationExecution> executions = medicationExecutionMapper.selectList(new QueryWrapper<MedicationExecution>()
                    .in("plan_id", planIds).eq("execution_date", day));
            for (MedicationExecution execution : executions) {
                executionMap.put(execution.getPlanId() + "#" + execution.getPeriod(), execution);
            }
        }
        int medicationDone = 0;
        int medicationPending = 0;
        int medicationSkipped = 0;
        for (MedicationPlan plan : plans) {
            for (String period : plan.getPeriods().split(",")) {
                MedicationExecution execution = executionMap.get(plan.getId() + "#" + period);
                String status = execution == null ? "PENDING" : execution.getStatus();
                if ("DONE".equals(status)) { medicationDone++; continue; }
                if ("SKIPPED".equals(status)) medicationSkipped++; else medicationPending++;
                String detail = plan.getMedicineName() + " · " + plan.getDoseInstruction();
                if (execution != null && execution.getNote() != null) detail += "；原因：" + execution.getNote();
                items.add(item("med-" + plan.getId() + "-" + period, "SKIPPED".equals(status) ? "MEDICATION_SKIPPED" : "MEDICATION_PENDING",
                        "SKIPPED".equals(status) ? "HIGH" : "NORMAL", plan.getId(), plan.getElderId(), day,
                        period + "间用药" + ("SKIPPED".equals(status) ? "未执行" : "待核对"), detail, status,
                        "/ai-care/medication", "请按已核对医嘱进入用药页面人工登记"));
            }
        }

        QueryWrapper<HealthData> todayHealthQuery = new QueryWrapper<HealthData>()
                .ge("measure_time", day).lt("measure_time", dayEnd);
        dataScopeService.applyElderScope(todayHealthQuery, "elder_id");
        Set<Long> healthElderIds = healthDataMapper.selectList(todayHealthQuery).stream()
                .map(HealthData::getElderId).collect(Collectors.toSet());
        int healthReviewCount = 0;
        if (!healthElderIds.isEmpty()) {
            List<HealthData> histories = healthDataMapper.selectList(new QueryWrapper<HealthData>()
                    .in("elder_id", healthElderIds).le("measure_time", dayEnd).orderByAsc("measure_time", "id"));
            Map<Long, List<HealthData>> grouped = histories.stream().collect(Collectors.groupingBy(HealthData::getElderId));
            for (Long elderId : healthElderIds) {
                List<String> reminders = healthChangeDetector.detect(grouped.getOrDefault(elderId, new ArrayList<>()));
                if (reminders.isEmpty()) continue;
                healthReviewCount++;
                HealthData latest = grouped.get(elderId).get(grouped.get(elderId).size() - 1);
                items.add(item("health-" + elderId, "HEALTH_CHANGE", "REVIEW", latest.getId(), elderId,
                        latest.getMeasureTime(), "健康数据变化需复核", String.join("；", reminders) + "。仅描述记录变化，请人工复核。",
                        "REVIEW", "/ai-care/health", "进入健康趋势页面结合院内流程人工复核"));
            }
        }

        QueryWrapper<NurseReserve> reserveCountQuery = new QueryWrapper<NurseReserve>().eq("order_flag", YesNoEnum.NO.getCode());
        dataScopeService.applyElderScope(reserveCountQuery, "elder_id");
        int serviceBacklogCount = nurseReserveMapper.selectCount(reserveCountQuery).intValue();
        QueryWrapper<NurseReserve> reserveListQuery = new QueryWrapper<NurseReserve>()
                .eq("order_flag", YesNoEnum.NO.getCode()).orderByAsc("create_time", "id").last("LIMIT 50");
        dataScopeService.applyElderScope(reserveListQuery, "elder_id");
        for (NurseReserve reserve : nurseReserveMapper.selectList(reserveListQuery)) {
            items.add(item("reserve-" + reserve.getId(), "SERVICE_BACKLOG", "NORMAL", reserve.getId(), reserve.getElderId(),
                    reserve.getCreateTime(), "未完成服务预约", reserve.getServiceName(), "PENDING", "/serve/book",
                    "前往服务预定页核对；执行会扣费并生成消费记录"));
        }

        fillElderNames(items);
        items.sort(Comparator.comparingInt(this::priority).thenComparing(item -> item.getEventTime() == null ? new Date(0) : item.getEventTime()));
        boolean truncated = items.size() > MAX_ITEMS;
        if (truncated) items = new ArrayList<>(items.subList(0, MAX_ITEMS));

        DailyCareOverviewVo result = new DailyCareOverviewVo();
        result.setDate(new SimpleDateFormat("yyyy-MM-dd").format(day));
        result.setGeneratedAt(new Date());
        result.setReadOnly(true);
        result.setModelUsed(false);
        result.setStatusBasis("CURRENT_STATE");
        result.setNotice("这是只读工作汇总。所有完成、执行、健康判断和扣费操作都必须进入原业务页面人工确认。");
        result.setTodayCareRecordCount(todayCareCount);
        result.setCarePendingCount(pendingCare.size());
        result.setMedicationDoneCount(medicationDone);
        result.setMedicationPendingCount(medicationPending);
        result.setMedicationSkippedCount(medicationSkipped);
        result.setHealthReviewCount(healthReviewCount);
        result.setServiceBacklogCount(serviceBacklogCount);
        result.setAttentionCount(pendingCare.size() + medicationPending + medicationSkipped + healthReviewCount + serviceBacklogCount);
        result.setTruncated(truncated);
        result.setItems(items);
        auditRecorder.record("每日护理助手", "生成只读汇总", "daily_overview", null,
                "日期=" + result.getDate() + "，关注项=" + result.getAttentionCount() + "，返回项=" + items.size());
        metricRecorder.record(AiMetricRecorder.Feature.DAILY_OVERVIEW, AiMetricRecorder.Stage.PIPELINE,
                AiMetricRecorder.Outcome.SUCCESS, "RULES", false, null,
                System.currentTimeMillis() - startedAt, result.getAttentionCount(), items.size(), null, null);
        return Result.success(result);
    }

    private DailyCareItemVo item(String key, String category, String level, Long sourceId, Long elderId, Date time,
                                 String title, String detail, String status, String path, String hint) {
        DailyCareItemVo item = new DailyCareItemVo();
        item.setKey(key); item.setCategory(category); item.setLevel(level); item.setSourceId(sourceId); item.setElderId(elderId);
        item.setEventTime(time); item.setTitle(title); item.setDetail(detail); item.setStatus(status); item.setTargetPath(path);
        item.setRequiresManualConfirmation(true); item.setConfirmationHint(hint);
        return item;
    }

    private void fillElderNames(List<DailyCareItemVo> items) {
        Set<Long> ids = items.stream().map(DailyCareItemVo::getElderId).filter(value -> value != null).collect(Collectors.toSet());
        Map<Long, Elder> elders = ids.isEmpty() ? new HashMap<>() : elderMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Elder::getId, value -> value));
        for (DailyCareItemVo item : items) {
            Elder elder = elders.get(item.getElderId());
            item.setElderName(elder == null ? "未知老人" : elder.getName());
        }
    }

    private int priority(DailyCareItemVo item) {
        switch (item.getCategory()) {
            case "MEDICATION_SKIPPED": return 1;
            case "HEALTH_CHANGE": return 2;
            case "CARE_FOLLOW_UP": return "REVIEW".equals(item.getLevel()) ? 3 : 4;
            case "MEDICATION_PENDING": return 5;
            default: return 6;
        }
    }

    private Date normalize(Date value) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(value);
        calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
}
