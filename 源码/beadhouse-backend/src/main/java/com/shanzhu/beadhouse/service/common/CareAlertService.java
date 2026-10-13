package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.DailyTaskActionQuery;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.stream.Collectors;

@Service
public class CareAlertService {
    @Resource private CareAlertMapper alertMapper;
    @Resource private DailyTaskMapper taskMapper;
    @Resource private HealthDataMapper healthMapper;
    @Resource private MedicationPlanMapper planMapper;
    @Resource private MedicationExecutionMapper executionMapper;
    @Resource private ElderMapper elderMapper;
    @Resource private HealthChangeDetector detector;
    @Resource private AiDataScopeService scope;
    @Resource private AuthorityAssert authority;
    @Resource private AiAuditRecorder audit;

    public void measurement(HealthData current) {
        scope.assertElderAccess(current.getElderId());
        List<HealthData> history=healthMapper.selectList(new QueryWrapper<HealthData>().eq("elder_id",current.getElderId())
            .and(q -> q.lt("measure_time",current.getMeasureTime()).or(w -> w.eq("measure_time",current.getMeasureTime()).le("id",current.getId())))
            .orderByDesc("measure_time","id").last("LIMIT 90"));
        Collections.reverse(history);
        List<String> reminders=detector.detectCurrent(history,current);
        if(!reminders.isEmpty()) store("health-"+current.getId(),current.getElderId(),current.getId(),"HEALTH_CHANGE",
                String.join("；",reminders)+"。仅描述数据变化，请人工复核。",current.getMeasureTime(),"/ai-care/health");
    }

    public void medication(MedicationPlan plan,MedicationExecution execution) {
        scope.assertElderAccess(plan.getElderId());
        String key=medicationKey(plan.getId(),execution.getExecutionDate(),execution.getPeriod());
        if("DONE".equals(execution.getStatus())) { alertMapper.resolveRecorded(key); return; }
        store(key,plan.getElderId(),plan.getId(),"MEDICATION_SKIPPED",
            execution.getPeriod()+"：已登记未执行 "+plan.getMedicineName()+"；原因："+execution.getNote(),
            execution.getExecutionDate(),"/ai-care/medication");
    }

    @Transactional
    public Result scan(Date date) {
        Date today=day(new Date()), requested=day(date==null?new Date(today.getTime()-86400000L):date);
        if(!requested.before(today) || requested.before(new Date(today.getTime()-31L*86400000L)))
            return Result.error(400,"仅可核对过去31天的日期；当天缺少记录不直接判定漏服");
        QueryWrapper<MedicationPlan> query=new QueryWrapper<MedicationPlan>().le("start_date",requested)
            .and(q -> q.isNull("end_date").or().ge("end_date",requested));
        scope.applyElderScope(query,"elder_id");
        query.orderByAsc("id");
        int count=0;
        for(MedicationPlan candidate:planMapper.selectList(query)) {
            MedicationPlan plan=planMapper.lockById(candidate.getId());
            if(plan==null || plan.getStartDate().after(requested) || (plan.getEndDate()!=null && plan.getEndDate().before(requested))) continue;
            scope.assertElderAccess(plan.getElderId());
            Map<String,MedicationExecution> executions=executionMapper.selectList(new QueryWrapper<MedicationExecution>()
                .eq("plan_id",plan.getId()).eq("execution_date",requested).last("FOR UPDATE")).stream()
                .collect(Collectors.toMap(MedicationExecution::getPeriod,e -> e));
            for(String period:plan.getPeriods().split(",")) {
                MedicationExecution execution=executions.get(period);
                if(execution!=null) { medication(plan,execution); if("SKIPPED".equals(execution.getStatus())) count++; }
                else { store(medicationKey(plan.getId(),requested,period),plan.getElderId(),plan.getId(),"MEDICATION_UNRECORDED",
                    period+"："+plan.getMedicineName()+" 缺少执行登记，需人工核对，不能据此认定漏服。",requested,"/ai-care/medication"); count++; }
            }
        }
        audit.record("护理告警","核对历史用药","ai_care_alert",null,"待核对项="+count);
        return Result.success("历史用药核对完成",count);
    }

    public Result list(String state) {
        if(!Arrays.asList("ACTIVE","ALL","OPEN","ACKNOWLEDGED","RESOLVED").contains(state)) return Result.error(400,"告警状态不合法");
        QueryWrapper<CareAlert> query=new QueryWrapper<CareAlert>(); scope.applyElderScope(query,"elder_id");
        if("ACTIVE".equals(state)) query.ne("state","RESOLVED"); else if(!"ALL".equals(state)) query.eq("state",state);
        List<CareAlert> alerts=alertMapper.selectList(query.orderByDesc("create_time","id").last("LIMIT 200"));
        Set<Long> ids=alerts.stream().map(CareAlert::getElderId).collect(Collectors.toSet());
        Map<Long,String> names=ids.isEmpty()?Collections.emptyMap():elderMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(Elder::getId,Elder::getName));
        alerts.forEach(a -> a.setElderName(names.get(a.getElderId()))); return Result.success(alerts);
    }

    @Transactional
    public Result act(String action,DailyTaskActionQuery query) {
        if(query==null || query.getId()==null || query.getRevision()==null) return Result.error(400,"告警编号和版本不能为空");
        CareAlert alert=alertMapper.lockById(query.getId());
        if(alert==null) throw new BusinessRuntimeException(404,"告警不存在");
        scope.assertElderAccess(alert.getElderId()); Long user=authority.getLoginUserId();
        if(!"ack".equals(action) && !user.equals(alert.getReviewerId()) && !scope.isAdmin()) return Result.error(403,"仅确认人或超级管理员可解除");
        if("resolve".equals(action) && "RESOLVED".equals(alert.getState())) return Result.success(alert);
        if("ack".equals(action) && "ACKNOWLEDGED".equals(alert.getState()) && user.equals(alert.getReviewerId())) return Result.success(alert);
        if(!Objects.equals(query.getRevision(),alert.getRevision())) return Result.error(409,"告警已变化，请刷新");
        if("ack".equals(action) && "OPEN".equals(alert.getState())) { alert.setState("ACKNOWLEDGED"); alert.setReviewerId(user); }
        else if("resolve".equals(action) && "ACKNOWLEDGED".equals(alert.getState())) {
            if(query.getNote()==null || query.getNote().trim().isEmpty() || query.getNote().length()>500) return Result.error(400,"请填写1至500字的解除依据");
            alert.setState("RESOLVED"); alert.setResolutionNote(query.getNote().trim()); alert.setResolvedAt(new Date());
        } else return Result.error(409,"请按待确认、已确认、已解除的顺序操作");
        alert.setRevision(alert.getRevision()+1); alert.setUpdateTime(new Date()); alertMapper.updateById(alert);
        audit.record("护理告警",action,"ai_care_alert",alert.getId(),null); return Result.success(alert);
    }

    private void store(String key,Long elderId,Long sourceId,String kind,String detail,Date time,String path) {
        CareAlert alert=new CareAlert(); alert.setAlertKey(key); alert.setTaskKey(key); alert.setElderId(elderId);
        alert.setSourceId(sourceId); alert.setKind(kind); alert.setDetail(detail); alert.setEvidenceKey(hash(kind+"\n"+detail));
        CareAlert previous=alertMapper.selectOne(new QueryWrapper<CareAlert>().eq("alert_key",key));
        if(previous!=null) alert.setTaskKey(previous.getTaskKey());
        if(previous==null || !alert.getEvidenceKey().equals(previous.getEvidenceKey())) {
            DailyTask previousTask=taskMapper.selectOne(new QueryWrapper<DailyTask>().eq("task_key",alert.getTaskKey()));
            if(previousTask!=null && "DONE".equals(previousTask.getState())) alert.setTaskKey(key+"-"+alert.getEvidenceKey().substring(0,12));
        }
        alertMapper.upsertEvidence(alert);
        DailyTask task=new DailyTask(); task.setTaskKey(alert.getTaskKey()); task.setTaskDate(day(time)); task.setElderId(elderId); task.setSourceId(sourceId);
        task.setCategory(kind); task.setTitle("HEALTH_CHANGE".equals(kind)?"健康数据变化复核":"用药记录待核对");
        task.setDetail(detail); task.setTargetPath(path); task.setDueAt(new Date(time.getTime()+86400000L));
        taskMapper.upsertSnapshot(task);
    }
    private String medicationKey(Long id,Date date,String period) { return new SimpleDateFormat("yyyy-MM-dd").format(date)+"-med-"+id+"-"+period; }
    private Date day(Date date) { Calendar c=Calendar.getInstance(); c.setTime(date); c.set(Calendar.HOUR_OF_DAY,0); c.set(Calendar.MINUTE,0); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0); return c.getTime(); }
    private String hash(String value) {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder s=new StringBuilder(); for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString(); }
        catch(Exception e) { throw new IllegalStateException("告警证据摘要不可用",e); }
    }
}
