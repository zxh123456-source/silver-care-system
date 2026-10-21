package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.dao.mapper.VisitMapper;
import com.shanzhu.beadhouse.entity.po.Visit;
import com.shanzhu.beadhouse.entity.query.AddVisitQuery;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
import com.shanzhu.beadhouse.service.common.VisitFunc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisitScopeTest {
    @Mock private VisitMapper visitMapper;
    @Mock private VisitFunc visitFunc;
    @Mock private AiDataScopeService dataScopeService;
    @InjectMocks private VisitServiceImpl service;

    @Test
    void editingCannotReplaceTheElderThroughAnExtendedRequest() {
        Visit stored = new Visit();
        stored.setId(7L);
        stored.setElderId(17L);
        when(visitFunc.checkVisit(7L, false)).thenReturn(stored);
        AddVisitQuery query = new AddVisitQuery();
        query.setId(7L);
        query.setElderId(99L);
        query.setName("访客");
        query.setPhone("test-phone");
        query.setRelation("家属");
        query.setVisitDate("2026-10-18 10:00:00");
        query.setVisitNum(2L);

        service.editVisit(query);

        assertThat(stored.getElderId()).isEqualTo(17L);
        assertThat(stored.getName()).isEqualTo("访客");
        assertThat(stored.getVisitNum()).isEqualTo(2);
        verify(dataScopeService).assertElderAccess(17L);
        verify(visitMapper).updateById(stored);
    }
}
