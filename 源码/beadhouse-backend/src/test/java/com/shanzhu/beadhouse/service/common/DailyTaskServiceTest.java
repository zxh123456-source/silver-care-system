package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.dao.mapper.DailyTaskMapper;
import com.shanzhu.beadhouse.entity.po.DailyTask;
import com.shanzhu.beadhouse.entity.query.DailyTaskActionQuery;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;

@ExtendWith(MockitoExtension.class)
class DailyTaskServiceTest {
    @Mock DailyTaskMapper taskMapper;
    @Mock AiDataScopeService scope;
    @Mock AuthorityAssert authority;
    @Mock AiAuditRecorder audit;
    @Mock com.shanzhu.beadhouse.service.AiDailyService dailyService;
    @InjectMocks DailyTaskService service;
    DailyTask task;
    DailyTaskActionQuery query;
    @BeforeEach void setUp() {
        task=new DailyTask(); task.setId(1L); task.setElderId(4L); task.setState("OPEN"); task.setRevision(0);
        query=new DailyTaskActionQuery(); query.setId(1L); query.setRevision(0);
    }
    private void load() { when(taskMapper.lockById(1L)).thenReturn(task); when(authority.getLoginUserId()).thenReturn(4L); }
    @Test void claimUsesCurrentUserAndDoesNotAcceptSpoofedOwner() {
        load(); query.setTargetStaffId(99L);
        assertThat(service.act("claim",query).getCode()).isEqualTo(200);
        assertThat(task.getOwnerId()).isEqualTo(4L); assertThat(task.getRevision()).isEqualTo(1);
        verify(taskMapper).updateById(task);
    }
    @Test void staleVersionCannotChangeTask() {
        load(); query.setRevision(7);
        assertThat(service.act("claim",query).getCode()).isEqualTo(409);
        verify(taskMapper,never()).updateById(any());
    }
    @Test void anotherAssigneeCannotCompleteTaskEvenWithElderAccess() {
        load(); task.setState("CLAIMED"); task.setOwnerId(5L); query.setNote("复核完成");
        assertThat(service.act("complete",query).getCode()).isEqualTo(403);
        verify(taskMapper,never()).updateById(any());
    }
    @Test void transferRejectsIneligibleRecipient() {
        load(); task.setState("CLAIMED"); task.setOwnerId(4L); query.setTargetStaffId(99L);
        when(taskMapper.eligibleOwners(eq(4L),anyLong())).thenReturn(Collections.emptyList());
        assertThat(service.act("transfer",query).getCode()).isEqualTo(400);
        verify(taskMapper,never()).updateById(any());
    }
    @Test void completionRequiresResultAndRepeatedCompletionIsIdempotent() {
        load(); task.setState("CLAIMED"); task.setOwnerId(4L);
        assertThat(service.act("complete",query).getCode()).isEqualTo(400);
        query.setNote("已人工复核");
        assertThat(service.act("complete",query).getCode()).isEqualTo(200);
        assertThat(service.act("complete",query).getCode()).isEqualTo(200);
        verify(taskMapper,times(1)).updateById(task); verify(audit,times(1)).record(anyString(),anyString(),anyString(),anyLong(),isNull());
    }
    @Test void revokedElderScopeRejectsBeforeAnyTaskChange() {
        when(taskMapper.lockById(1L)).thenReturn(task);
        doThrow(new BusinessRuntimeException(403,"无权访问")).when(scope).assertElderAccess(4L);
        assertThatThrownBy(() -> service.act("claim",query)).isInstanceOf(BusinessRuntimeException.class);
        verify(taskMapper,never()).updateById(any()); verifyNoInteractions(authority,audit);
    }
    @Test void overdueIsDerivedAndCompletedTaskIsNotOverdue() {
        task.setDueAt(new Date(System.currentTimeMillis()-60000)); assertThat(task.isOverdue()).isTrue();
        task.setState("DONE"); assertThat(task.isOverdue()).isFalse();
    }
    @Test void sourceKeysKeepCareStableButSeparateMedicationDatesAndHealthMeasurements() {
        com.shanzhu.beadhouse.entity.vo.DailyCareOverviewVo overview=new com.shanzhu.beadhouse.entity.vo.DailyCareOverviewVo();
        overview.setDate("2026-10-08");
        List<com.shanzhu.beadhouse.entity.vo.DailyCareItemVo> items=new ArrayList<>();
        for(String category:Arrays.asList("CARE_FOLLOW_UP","MEDICATION_PENDING","HEALTH_CHANGE")) {
            com.shanzhu.beadhouse.entity.vo.DailyCareItemVo item=new com.shanzhu.beadhouse.entity.vo.DailyCareItemVo();
            item.setCategory(category); item.setElderId(4L); item.setSourceId(10L);
            item.setKey(category.equals("CARE_FOLLOW_UP")?"care-10":category.equals("HEALTH_CHANGE")?"health-4":"med-10-早");
            items.add(item);
        }
        overview.setItems(items);
        when(dailyService.overview(any())).thenReturn(com.shanzhu.beadhouse.entity.base.Result.success(overview));
        service.sync(new Date());
        overview.setDate("2026-10-09"); items.get(2).setSourceId(11L);
        service.sync(new Date());
        ArgumentCaptor<DailyTask> capture=ArgumentCaptor.forClass(DailyTask.class);
        verify(taskMapper,times(6)).upsertSnapshot(capture.capture());
        List<String> keys=new ArrayList<>(); for(DailyTask value:capture.getAllValues()) keys.add(value.getTaskKey());
        assertThat(keys).containsExactly("care-10","2026-10-08-med-10-早","health-10",
            "care-10","2026-10-09-med-10-早","health-11");
    }
}
