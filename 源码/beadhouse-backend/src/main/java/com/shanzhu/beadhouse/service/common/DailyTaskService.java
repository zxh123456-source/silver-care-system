package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.base.*;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.query.DailyTaskActionQuery;
import com.shanzhu.beadhouse.entity.vo.*;
import com.shanzhu.beadhouse.service.AiDailyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DailyTaskService {
    @Resource private DailyTaskMapper taskMapper;
    @Resource private AiDailyService dailyService;
    @Resource private AiDataScopeService scope;
    @Resource private AuthorityAssert authority;
    @Resource private ElderMapper elderMapper;
    @Resource private StaffMapper staffMapper;
    @Resource private AiAuditRecorder audit;
    @Value("${ai.data-scope.admin-role-id:1}") private long adminRoleId;

    @Transactional
    public Result sync(Date requestedDate) {
        Result overview=dailyService.overview(requestedDate);
        if (overview.getCode()!=200) return overview;
        DailyCareOverviewVo data=(DailyCareOverviewVo)overview.getData();
        Date day=parseDay(data.getDate());
        for(DailyCareItemVo item:data.getItems()) {
            scope.assertElderAccess(item.getElderId());
            DailyTask task=new DailyTask();
            String key=item.getKey();
            if(item.getCategory().startsWith("MEDICATION_")) key=data.getDate()+"-"+key;
            if("HEALTH_CHANGE".equals(item.getCategory())) key="health-"+item.getSourceId();
            task.setTaskKey(key); task.setTaskDate(day); task.setElderId(item.getElderId());
            task.setSourceId(item.getSourceId()); task.setCategory(item.getCategory());
            task.setTitle(item.getTitle()); task.setDetail(item.getDetail()); task.setTargetPath(item.getTargetPath());
            Calendar deadline=Calendar.getInstance(); deadline.setTime(item.getEventTime()==null?day:item.getEventTime());
            deadline.add(Calendar.HOUR_OF_DAY,24); task.setDueAt(deadline.getTime());
            taskMapper.upsertSnapshot(task);
        }
        audit.record("每日任务","同步任务","ai_daily_task",null,"来源项="+data.getItems().size());
        return Result.success("任务已同步；已完成任务不会重新打开",data.getItems().size());
    }

    public Result list(Date date,String state) {
        if(!Arrays.asList("ALL","ACTIVE","OPEN","CLAIMED","DONE").contains(state)) return Result.error(400,"任务状态不合法");
        QueryWrapper<DailyTask> query=new QueryWrapper<DailyTask>();
        scope.applyElderScope(query,"elder_id");
        if(date!=null) query.le("task_date",date);
        if("ACTIVE".equals(state)) query.ne("state","DONE");
        else if(!"ALL".equals(state)) query.eq("state",state);
        query.orderByAsc("due_at","id").last("LIMIT 200");
        List<DailyTask> tasks=taskMapper.selectList(query);
        Set<Long> elders=tasks.stream().map(DailyTask::getElderId).collect(Collectors.toSet());
        Set<Long> owners=tasks.stream().map(DailyTask::getOwnerId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long,String> names=elders.isEmpty()?Collections.emptyMap():elderMapper.selectBatchIds(elders).stream().collect(Collectors.toMap(Elder::getId,Elder::getName));
        Map<Long,String> staff=owners.isEmpty()?Collections.emptyMap():staffMapper.selectBatchIds(owners).stream().collect(Collectors.toMap(Staff::getId,Staff::getName));
        for(DailyTask task:tasks) { task.setElderName(names.get(task.getElderId())); task.setOwnerName(staff.get(task.getOwnerId())); }
        return Result.success(tasks);
    }

    public Result owners(Long id) {
        DailyTask task=taskMapper.selectById(id); requireTask(task);
        scope.assertElderAccess(task.getElderId());
        return Result.success(taskMapper.eligibleOwners(task.getElderId(),adminRoleId));
    }

    @Transactional
    public Result act(String action,DailyTaskActionQuery query) {
        if(query==null || query.getId()==null || query.getRevision()==null) return Result.error(400,"任务编号和版本不能为空");
        DailyTask task=taskMapper.lockById(query.getId()); requireTask(task);
        scope.assertElderAccess(task.getElderId());
        Long operator=authority.getLoginUserId();
        if("claim".equals(action)) {
            if("CLAIMED".equals(task.getState()) && operator.equals(task.getOwnerId())) return Result.success(task);
            if(!"OPEN".equals(task.getState())) return Result.error(409,"任务已被认领或完成，请刷新");
        } else {
            if(!operator.equals(task.getOwnerId()) && !scope.isAdmin()) return Result.error(403,"仅任务负责人或超级管理员可操作");
            if("complete".equals(action) && "DONE".equals(task.getState())) return Result.success(task);
            if(!"CLAIMED".equals(task.getState())) return Result.error(409,"请先认领任务");
        }
        if(!Objects.equals(task.getRevision(),query.getRevision())) return Result.error(409,"任务已变化，请刷新后重试");
        if("claim".equals(action)) { task.setOwnerId(operator); task.setState("CLAIMED"); }
        else if("transfer".equals(action)) {
            boolean eligible=taskMapper.eligibleOwners(task.getElderId(),adminRoleId).stream()
                    .anyMatch(owner -> Objects.equals(owner.getId(),query.getTargetStaffId()));
            if(!eligible) return Result.error(400,"接收人必须在职并拥有该老人及每日助手权限");
            task.setOwnerId(query.getTargetStaffId());
        } else if("complete".equals(action)) {
            if(query.getNote()==null || query.getNote().trim().isEmpty() || query.getNote().length()>500)
                return Result.error(400,"请填写不超过500字的复核结果");
            task.setCompletionNote(query.getNote().trim()); task.setCompletedAt(new Date()); task.setState("DONE");
        } else return Result.error(400,"任务操作不合法");
        task.setRevision(task.getRevision()+1); task.setUpdateTime(new Date()); taskMapper.updateById(task);
        audit.record("每日任务",action,"ai_daily_task",task.getId(),null);
        return Result.success(task);
    }

    private void requireTask(DailyTask task) { if(task==null) throw new BusinessRuntimeException(404,"任务不存在"); }
    private Date parseDay(String date) {
        try { return new SimpleDateFormat("yyyy-MM-dd").parse(date); }
        catch(Exception e) { throw new BusinessRuntimeException(400,"日期不合法"); }
    }
}
