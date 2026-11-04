package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.dao.mapper.ActiveMapper;
import com.shanzhu.beadhouse.dao.mapper.ActiveParticipantMapper;
import com.shanzhu.beadhouse.entity.po.Active;
import com.shanzhu.beadhouse.entity.po.ActiveParticipant;
import com.shanzhu.beadhouse.entity.query.OperateActiveQuery;
import com.shanzhu.beadhouse.service.common.ActiveFunc;
import com.shanzhu.beadhouse.service.common.ActiveParticipantFunc;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
import com.shanzhu.beadhouse.service.common.AttachmentAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityScopeMutationTest {
    @Mock ActiveMapper activeMapper;
    @Mock ActiveParticipantMapper activeParticipantMapper;
    @Mock ActiveParticipantFunc activeParticipantFunc;
    @Mock ActiveFunc activeFunc;
    @Mock AiDataScopeService dataScopeService;
    @Mock AttachmentAccessService attachmentAccessService;
    @InjectMocks ActiveServiceImpl service;

    @BeforeEach void scope() {
        lenient().doThrow(new BusinessRuntimeException(403, "无权访问该老人数据"))
                .when(dataScopeService).assertElderAccess(99L);
        Active active = new Active();
        active.setId(7L);
        active.setName("护理活动");
        active.setDelFlag("N");
        lenient().when(activeMapper.selectById(7L)).thenReturn(active);
        lenient().when(activeMapper.selectByIdForUpdate(7L)).thenReturn(active);
    }

    @Test void addingAnActivityChecksEveryProposedParticipantBeforeAnyWrite() {
        denied(() -> service.addActive(query(17L, 99L)));
        verify(activeMapper, never()).insert(any());
        verifyNoInteractions(activeParticipantFunc, attachmentAccessService);
    }

    @Test void detailsCannotExposeParticipantsOutsideTheCurrentElderScope() {
        participants(17L, 99L);
        denied(() -> service.getActiveById(7L));
        verify(activeParticipantMapper, never()).listParticipateElder(any());
    }

    @Test void removingAnUnassignedParticipantCannotLaunderAccessToTheActivity() {
        participants(17L, 99L);
        denied(() -> service.editActive(query(17L)));
        verify(activeMapper, never()).updateById(any());
        verifyNoInteractions(activeParticipantFunc, attachmentAccessService);
    }

    @Test void editingChecksNewParticipantsAsWellAsExistingParticipants() {
        participants(17L);
        denied(() -> service.editActive(query(17L, 99L)));
        verify(activeMapper, never()).updateById(any());
        verifyNoInteractions(activeParticipantFunc, attachmentAccessService);
    }

    @Test void deletingAnActivityChecksEveryExistingParticipantBeforeAnyWrite() {
        participants(17L, 99L);
        denied(() -> service.deleteActive(7L));
        verify(activeMapper, never()).updateById(any());
        verifyNoInteractions(activeParticipantFunc, attachmentAccessService);
    }

    @Test void authorizedParticipantReplacementPersistsTheCanonicalPhotoUrl() {
        participants(17L);
        OperateActiveQuery query = query(17L, 18L);
        query.setActivePicture("/file/21/content");
        when(attachmentAccessService.bind("ACTIVE", 7L, null, "/file/21/content"))
                .thenReturn("/file/21/content?canonical=true");

        assertThat(service.editActive(query).getCode()).isEqualTo(200);

        verify(dataScopeService, times(2)).assertElderAccess(17L);
        verify(dataScopeService).assertElderAccess(18L);
        verify(activeParticipantFunc).saveBatchActiveParticipant(Arrays.asList(17L, 18L), 7L, true);
        verify(activeMapper).updateById(argThat(value -> value.getId().equals(7L)
                && "/file/21/content?canonical=true".equals(value.getActivePicture())
                && value.getDelFlag() == null));
    }

    @Test void institutionActivityWithNoParticipantsRemainsReadableAndEditable() {
        participants();
        assertThat(service.getActiveById(7L).getCode()).isEqualTo(200);
        assertThat(service.editActive(query()).getCode()).isEqualTo(200);
        verifyNoInteractions(dataScopeService);
        verify(activeParticipantFunc).saveBatchActiveParticipant(Collections.emptyList(), 7L, true);
    }

    @Test void omittedActivityPhotoIsPreservedAndExplicitEmptyPhotoIsCleared() {
        participants(17L);
        Active stored = new Active();
        stored.setId(7L);
        stored.setDelFlag("N");
        stored.setActivePicture("legacy-unrecognized-url");
        when(activeMapper.selectByIdForUpdate(7L)).thenReturn(stored);
        when(attachmentAccessService.bind("ACTIVE", 7L, "legacy-unrecognized-url", "legacy-unrecognized-url"))
                .thenReturn("legacy-unrecognized-url");
        when(attachmentAccessService.bind("ACTIVE", 7L, "legacy-unrecognized-url", "")).thenReturn("");
        OperateActiveQuery query = query(17L);

        service.editActive(query);
        verify(activeMapper).updateById(argThat(value -> "legacy-unrecognized-url".equals(value.getActivePicture())));

        query.setActivePicture("");
        service.editActive(query);
        verify(attachmentAccessService).bind("ACTIVE", 7L, "legacy-unrecognized-url", "");
        verify(activeMapper).updateById(argThat(value -> "".equals(value.getActivePicture())));
    }

    @Test void authorizedActivityDeletionRetiresThePhotoWithoutDroppingItsParticipants() {
        participants(17L);
        service.deleteActive(7L);

        verify(dataScopeService).assertElderAccess(17L);
        verify(activeMapper).updateById(argThat(value -> value.getId().equals(7L) && "Y".equals(value.getDelFlag())));
        verify(attachmentAccessService).retire("ACTIVE", 7L);
        verify(activeParticipantMapper, never()).delete(any());
        verifyNoInteractions(activeParticipantFunc);
    }

    @Test void omittedParticipantListCannotEraseExistingParticipants() {
        participants(17L);
        OperateActiveQuery query = query();
        query.setElderIdList(null);
        assertThatThrownBy(() -> service.editActive(query)).isInstanceOfSatisfying(BusinessRuntimeException.class,
                error -> assertThat(error.getCode()).isEqualTo(400));
        verify(activeMapper, never()).updateById(any());
        verifyNoInteractions(activeParticipantFunc, attachmentAccessService);
    }

    private void participants(Long... ids) {
        lenient().when(activeParticipantMapper.selectList(any())).thenReturn(Arrays.stream(ids).map(id -> {
            ActiveParticipant participant = new ActiveParticipant();
            participant.setActiveId(7L);
            participant.setElderId(id);
            return participant;
        }).collect(Collectors.toList()));
    }

    private OperateActiveQuery query(Long... ids) {
        OperateActiveQuery query = new OperateActiveQuery();
        query.setId(7L);
        query.setName("护理活动");
        query.setElderIdList(Arrays.asList(ids));
        return query;
    }

    private void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessRuntimeException.class,
                error -> assertThat(error.getCode()).isEqualTo(403));
    }
}
