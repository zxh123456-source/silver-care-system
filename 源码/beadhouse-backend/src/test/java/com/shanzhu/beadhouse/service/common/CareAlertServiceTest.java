package com.shanzhu.beadhouse.service.common;
import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.query.DailyTaskActionQuery;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CareAlertServiceTest {
    @Mock CareAlertMapper alertMapper;
    @Mock DailyTaskMapper taskMapper;
    @Mock MedicationPlanMapper planMapper;
    @Mock AiDataScopeService scope;
    @Mock AuthorityAssert authority;
    @Mock AiAuditRecorder audit;
    @InjectMocks CareAlertService service;
    @Test void doneRegistrationClosesAlertWithoutExecutingAnyTask() {
        MedicationPlan plan=new MedicationPlan(); plan.setId(1L); plan.setElderId(4L);
        MedicationExecution execution=new MedicationExecution(); execution.setExecutionDate(new Date()); execution.setPeriod("早"); execution.setStatus("DONE");
        service.medication(plan,execution);
        verify(alertMapper).resolveRecorded(anyString()); verifyNoInteractions(taskMapper);
    }
    @Test void newEvidenceGetsNewTaskWhenPreviousTaskWasCompleted() {
        MedicationPlan plan=new MedicationPlan(); plan.setId(1L); plan.setElderId(4L); plan.setMedicineName("测试");
        MedicationExecution execution=new MedicationExecution(); execution.setExecutionDate(new Date()); execution.setPeriod("早"); execution.setStatus("SKIPPED"); execution.setNote("已人工核对");
        DailyTask completed=new DailyTask(); completed.setState("DONE");
        when(taskMapper.selectOne(any())).thenReturn(completed);
        service.medication(plan,execution);
        ArgumentCaptor<CareAlert> alert=ArgumentCaptor.forClass(CareAlert.class); verify(alertMapper).upsertEvidence(alert.capture());
        ArgumentCaptor<DailyTask> task=ArgumentCaptor.forClass(DailyTask.class); verify(taskMapper).upsertSnapshot(task.capture());
        assertThat(task.getValue().getTaskKey()).isEqualTo(alert.getValue().getTaskKey());
        assertThat(task.getValue().getTaskKey()).isNotEqualTo(alert.getValue().getAlertKey());
    }
    @Test void todayCannotBeScannedAsMissingMedication() {
        assertThat(service.scan(new Date()).getCode()).isEqualTo(400);
        verifyNoInteractions(planMapper,alertMapper,taskMapper);
    }
    @Test void otherStaffCannotResolveAndRevisionConflictsAreRejected() {
        CareAlert alert=new CareAlert(); alert.setElderId(4L); alert.setReviewerId(5L); alert.setState("ACKNOWLEDGED"); alert.setRevision(2);
        when(alertMapper.lockById(1L)).thenReturn(alert); when(authority.getLoginUserId()).thenReturn(4L);
        DailyTaskActionQuery q=new DailyTaskActionQuery(); q.setId(1L); q.setRevision(1); q.setNote("复核");
        assertThat(service.act("resolve",q).getCode()).isEqualTo(403);
        alert.setReviewerId(4L);
        assertThat(service.act("resolve",q).getCode()).isEqualTo(409);
        verify(alertMapper,never()).updateById(any());
    }
}
