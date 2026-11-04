package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.dao.mapper.AccidentMapper;
import com.shanzhu.beadhouse.entity.po.Accident;
import com.shanzhu.beadhouse.entity.query.AddAccidentQuery;
import com.shanzhu.beadhouse.entity.query.EditAccidentQuery;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
import com.shanzhu.beadhouse.service.common.AttachmentAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccidentAttachmentMutationTest {
    @Mock AccidentMapper accidentMapper;
    @Mock AiDataScopeService dataScopeService;
    @Mock AttachmentAccessService attachmentAccessService;
    @InjectMocks AccidentServiceImpl service;

    @Test void extendedEditRequestCannotChangeTheElderThatOwnsThePhoto() {
        Accident stored = new Accident();
        stored.setId(7L);
        stored.setElderId(17L);
        stored.setDelFlag("N");
        when(accidentMapper.selectByIdForUpdate(7L)).thenReturn(stored);
        AddAccidentQuery query = new AddAccidentQuery();
        query.setId(7L);
        query.setElderId(99L);
        query.setDescription("本次处理记录");
        query.setOccurDate("2026-10-19 10:00:00");

        service.editAccident(query);

        ArgumentCaptor<Accident> written = ArgumentCaptor.forClass(Accident.class);
        verify(accidentMapper).updateById(written.capture());
        assertThat(written.getValue().getElderId()).isIn(null, 17L);
        assertThat(written.getValue().getDescription()).isEqualTo("本次处理记录");
        verify(dataScopeService).assertElderAccess(17L);
        verify(dataScopeService, never()).assertElderAccess(99L);
    }

    @Test void replacingThePhotoUsesTheLockedDatabaseUrlAndPersistsOnlyTheCanonicalUrl() {
        storedPhoto("/file/20/content");
        EditAccidentQuery query = new EditAccidentQuery();
        query.setId(7L);
        query.setPicture("/file/21/content");
        when(attachmentAccessService.bind("ACCIDENT", 7L, "/file/20/content", "/file/21/content"))
                .thenReturn("/file/21/content?canonical=true");

        service.editAccident(query);

        verify(accidentMapper).updateById(argThat(value -> "/file/21/content?canonical=true".equals(value.getPicture())
                && value.getElderId() == null && value.getDelFlag() == null));
    }

    @Test void omittedPhotoPreservesTheLegacyValueAndExplicitEmptyPhotoClearsIt() {
        storedPhoto("legacy-unrecognized-url");
        EditAccidentQuery query = new EditAccidentQuery();
        query.setId(7L);
        when(attachmentAccessService.bind("ACCIDENT", 7L, "legacy-unrecognized-url", "legacy-unrecognized-url"))
                .thenReturn("legacy-unrecognized-url");
        service.editAccident(query);
        verify(accidentMapper).updateById(argThat(value -> "legacy-unrecognized-url".equals(value.getPicture())));

        query.setPicture("");
        service.editAccident(query);
        verify(attachmentAccessService).bind("ACCIDENT", 7L, "legacy-unrecognized-url", "");
        verify(accidentMapper).updateById(argThat(value -> "".equals(value.getPicture())));
    }

    @Test void failedPhotoBindingDoesNotWriteTheNewIncidentDescription() {
        storedPhoto("/file/20/content");
        EditAccidentQuery query = new EditAccidentQuery();
        query.setId(7L);
        query.setDescription("不能部分保存");
        query.setPicture("/file/21/content");
        when(attachmentAccessService.bind("ACCIDENT", 7L, "/file/20/content", "/file/21/content"))
                .thenThrow(new IllegalStateException("reference write failed"));

        assertThatThrownBy(() -> service.editAccident(query)).isInstanceOf(IllegalStateException.class);

        verify(accidentMapper, never()).updateById(any());
    }

    @Test void deletingTheIncidentRetiresItsPhotoReference() {
        storedPhoto("/file/20/content");
        service.deleteAccident(7L);
        verify(accidentMapper).updateById(argThat(value -> value.getId().equals(7L) && "Y".equals(value.getDelFlag())));
        verify(attachmentAccessService).retire("ACCIDENT", 7L);
    }

    private void storedPhoto(String picture) {
        Accident stored = new Accident();
        stored.setId(7L);
        stored.setElderId(17L);
        stored.setDelFlag("N");
        stored.setPicture(picture);
        when(accidentMapper.selectByIdForUpdate(7L)).thenReturn(stored);
    }
}
