package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.shanzhu.beadhouse.dao.mapper.CareNoteMapper;
import com.shanzhu.beadhouse.entity.po.CareNote;
import com.shanzhu.beadhouse.entity.query.FamilyReportQuery;
import com.shanzhu.beadhouse.service.common.AiAuditRecorder;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
import com.shanzhu.beadhouse.service.common.AiMetricRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiCareProviderGuardTest {
    @Test
    void disabledProviderNeverReceivesFamilyReportRecords() {
        GuardedService service = new GuardedService();
        CareNoteMapper mapper = mock(CareNoteMapper.class);
        CareNote note = new CareNote(); note.setElderId(17L); note.setObservation("SENSITIVE_MARKER");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(note));
        ReflectionTestUtils.setField(service, "careNoteMapper", mapper);
        ReflectionTestUtils.setField(service, "dataScopeService", mock(AiDataScopeService.class));
        ReflectionTestUtils.setField(service, "auditRecorder", mock(AiAuditRecorder.class));
        ReflectionTestUtils.setField(service, "metricRecorder", mock(AiMetricRecorder.class));
        ReflectionTestUtils.setField(service, "providerEnabled", false);
        ReflectionTestUtils.setField(service, "providerUrl", "http://should-never-be-called");
        ReflectionTestUtils.setField(service, "providerApiKey", "configured-but-disabled");
        FamilyReportQuery query = new FamilyReportQuery();
        query.setElderId(17L); query.setStartDate(new Date(0)); query.setEndDate(new Date());

        service.familyReport(query);

        assertThat(service.providerCalled).isFalse();
    }

    private static class GuardedService extends AiCareServiceImpl {
        private boolean providerCalled;

        @Override
        protected String providerFamilyReport(java.util.List<CareNote> notes) {
            providerCalled = true;
            return "unexpected";
        }
    }
}
