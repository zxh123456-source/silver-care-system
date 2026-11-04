package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.dao.mapper.CommunicationRecordMapper;
import com.shanzhu.beadhouse.dao.mapper.LabelMapper;
import com.shanzhu.beadhouse.dao.mapper.VisitPlanMapper;
import com.shanzhu.beadhouse.entity.po.CommunicationRecord;
import com.shanzhu.beadhouse.entity.po.VisitPlan;
import com.shanzhu.beadhouse.entity.query.*;
import com.shanzhu.beadhouse.service.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntentionChildScopeTest {
    @Mock private AiDataScopeService scope;
    @Mock private LabelMapper labelMapper;
    @Mock private LabelTypeFunc labelTypeFunc;
    @Mock private LabelFunc labelFunc;
    @Mock private ElderLabelFunc elderLabelFunc;
    @Mock private VisitPlanMapper visitPlanMapper;
    @Mock private VisitPlanFunc visitPlanFunc;
    @Mock private CommunicationRecordMapper communicationRecordMapper;
    @Mock private CommunicationRecordFunc communicationRecordFunc;
    @InjectMocks private IntentionServiceImpl service;

    @Test
    void labelReadsAndReplacementRejectUnassignedEldersBeforeAnyDataAccess() {
        rejectElder(8L);
        EditElderLabelQuery query = new EditElderLabelQuery();
        query.setElderId(8L);
        query.setLabelIdList(Collections.singletonList(1L));

        denied(() -> service.getElderLabelById(8L));
        denied(() -> service.getEditElderLabelById(8L));
        denied(() -> service.editElderLabel(query));

        verifyNoInteractions(labelMapper, labelTypeFunc, labelFunc, elderLabelFunc);
    }

    @Test
    void visitPlanReadsAndCreationRejectUnassignedEldersBeforeAnyDataAccess() {
        rejectElder(8L);
        PageVisitPlanQuery page = new PageVisitPlanQuery();
        page.setElderId(8L);
        page.setCompleteFlag(false);
        AddVisitPlanQuery add = new AddVisitPlanQuery();
        add.setElderId(8L);
        add.setTitle("回访");
        add.setPlanDate("2026-10-19");

        denied(() -> service.pageVisitPlan(page));
        denied(() -> service.addVisitPlan(add));

        verifyNoInteractions(visitPlanMapper, visitPlanFunc);
    }

    @Test
    void communicationReadsAndCreationRejectUnassignedEldersBeforeAnyDataAccess() {
        rejectElder(8L);
        PageCommunicationRecordQuery page = new PageCommunicationRecordQuery();
        page.setElderId(8L);
        AddCommunicationRecordQuery add = new AddCommunicationRecordQuery();
        add.setElderId(8L);
        add.setCommunicationRecord("沟通内容");
        add.setRecordDate("2026-10-19");

        denied(() -> service.pageCommunicationRecord(page));
        denied(() -> service.addCommunicationRecord(add));

        verifyNoInteractions(communicationRecordMapper, communicationRecordFunc);
    }

    @Test
    void executingAndDeletingPlansAuthorizeTheStoredElderBeforeWriting() {
        rejectElder(8L);
        VisitPlan stored = new VisitPlan();
        stored.setId(99L);
        stored.setElderId(8L);
        when(visitPlanMapper.selectById(99L)).thenReturn(stored);
        ExtendedCompleteVisitPlanQuery query = new ExtendedCompleteVisitPlanQuery();
        query.setId(99L);
        query.setContent("伪造完成记录");
        query.setCompleteDate("2026-10-19");

        denied(() -> service.executeVisitPlan(query));
        denied(() -> service.deleteVisitPlan(99L));

        verify(scope, times(2)).assertElderAccess(8L);
        verify(visitPlanMapper, never()).updateById(any());
    }

    @Test
    void editingAndDeletingCommunicationsAuthorizeTheStoredElderBeforeWriting() {
        rejectElder(8L);
        CommunicationRecord stored = new CommunicationRecord();
        stored.setId(99L);
        stored.setElderId(8L);
        when(communicationRecordMapper.selectById(99L)).thenReturn(stored);
        ExtendedEditCommunicationRecordQuery query = new ExtendedEditCommunicationRecordQuery();
        query.setId(99L);
        query.setCommunicationRecord("伪造沟通记录");
        query.setRecordDate("2026-10-19");

        denied(() -> service.editCommunicationRecord(query));
        denied(() -> service.deleteCommunicationRecord(99L));

        verify(scope, times(2)).assertElderAccess(8L);
        verify(communicationRecordMapper, never()).updateById(any());
    }

    @Test
    void planExecutionOnlyUpdatesCompletionFieldsAfterAuthorizingTheStoredElder() {
        VisitPlan stored = new VisitPlan();
        stored.setId(7L);
        stored.setElderId(17L);
        stored.setTitle("原回访标题");
        stored.setDelFlag("N");
        when(visitPlanMapper.selectById(7L)).thenReturn(stored);
        ExtendedCompleteVisitPlanQuery query = new ExtendedCompleteVisitPlanQuery();
        query.setId(7L);
        query.setContent("完成回访");
        query.setCompleteDate("2026-10-19");

        service.executeVisitPlan(query);

        ArgumentCaptor<VisitPlan> update = ArgumentCaptor.forClass(VisitPlan.class);
        verify(visitPlanMapper).updateById(update.capture());
        assertThat(update.getValue().getId()).isEqualTo(7L);
        assertThat(update.getValue().getContent()).isEqualTo("完成回访");
        assertThat(update.getValue().getCompleteDate()).isNotNull();
        // 增量实体不得包含归属、删除状态或标题的更新。
        assertThat(update.getValue().getElderId()).isNull();
        assertThat(update.getValue().getDelFlag()).isNull();
        assertThat(update.getValue().getTitle()).isNull();
        verify(scope).assertElderAccess(17L);
    }

    @Test
    void communicationEditOnlyUpdatesContentAndDateAfterAuthorizingTheStoredElder() {
        CommunicationRecord stored = new CommunicationRecord();
        stored.setId(7L);
        stored.setElderId(17L);
        stored.setDelFlag("N");
        when(communicationRecordMapper.selectById(7L)).thenReturn(stored);
        ExtendedEditCommunicationRecordQuery query = new ExtendedEditCommunicationRecordQuery();
        query.setId(7L);
        query.setCommunicationRecord("更新沟通记录");
        query.setRecordDate("2026-10-19");

        service.editCommunicationRecord(query);

        ArgumentCaptor<CommunicationRecord> update = ArgumentCaptor.forClass(CommunicationRecord.class);
        verify(communicationRecordMapper).updateById(update.capture());
        assertThat(update.getValue().getId()).isEqualTo(7L);
        assertThat(update.getValue().getCommunicationRecord()).isEqualTo("更新沟通记录");
        assertThat(update.getValue().getRecordDate()).isNotNull();
        // 增量实体不得包含归属或删除状态的更新。
        assertThat(update.getValue().getElderId()).isNull();
        assertThat(update.getValue().getDelFlag()).isNull();
        verify(scope).assertElderAccess(17L);
    }

    @Test
    void missingChildRecordIdsCannotReachUpdates() {
        CompleteVisitPlanQuery plan = new CompleteVisitPlanQuery();
        EditCommunicationRecordQuery communication = new EditCommunicationRecordQuery();
        assertThatThrownBy(() -> service.executeVisitPlan(plan)).isInstanceOf(BusinessRuntimeException.class);
        assertThatThrownBy(() -> service.deleteVisitPlan(null)).isInstanceOf(BusinessRuntimeException.class);
        assertThatThrownBy(() -> service.editCommunicationRecord(communication)).isInstanceOf(BusinessRuntimeException.class);
        assertThatThrownBy(() -> service.deleteCommunicationRecord(null)).isInstanceOf(BusinessRuntimeException.class);
        plan.setId(404L);
        communication.setId(404L);
        assertThatThrownBy(() -> service.executeVisitPlan(plan)).isInstanceOf(BusinessRuntimeException.class);
        assertThatThrownBy(() -> service.deleteVisitPlan(404L)).isInstanceOf(BusinessRuntimeException.class);
        assertThatThrownBy(() -> service.editCommunicationRecord(communication)).isInstanceOf(BusinessRuntimeException.class);
        assertThatThrownBy(() -> service.deleteCommunicationRecord(404L)).isInstanceOf(BusinessRuntimeException.class);

        verify(visitPlanMapper, never()).updateById(any());
        verify(communicationRecordMapper, never()).updateById(any());
        verifyNoInteractions(scope);
    }

    private void rejectElder(Long elderId) {
        doThrow(new BusinessRuntimeException(403, "无权访问该老人数据"))
                .when(scope).assertElderAccess(elderId);
    }

    private void denied(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(BusinessRuntimeException.class)
                .hasMessageContaining("无权访问");
    }

    public static class ExtendedCompleteVisitPlanQuery extends CompleteVisitPlanQuery {
        public Long getElderId() { return 99L; }
        public String getDelFlag() { return "Y"; }
        public String getTitle() { return "伪造标题"; }
    }

    public static class ExtendedEditCommunicationRecordQuery extends EditCommunicationRecordQuery {
        public Long getElderId() { return 99L; }
        public String getDelFlag() { return "Y"; }
    }
}
