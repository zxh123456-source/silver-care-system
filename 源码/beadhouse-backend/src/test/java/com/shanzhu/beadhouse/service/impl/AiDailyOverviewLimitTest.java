package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.vo.DailyCareOverviewVo;
import com.shanzhu.beadhouse.service.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AiDailyOverviewLimitTest {
    @Mock CareNoteMapper careNoteMapper;
    @Mock MedicationPlanMapper medicationPlanMapper;
    @Mock MedicationExecutionMapper medicationExecutionMapper;
    @Mock HealthDataMapper healthDataMapper;
    @Mock NurseReserveMapper nurseReserveMapper;
    @Mock ElderMapper elderMapper;
    @Mock AiDataScopeService dataScopeService;
    @Mock HealthChangeDetector healthChangeDetector;
    @Mock AiAuditRecorder auditRecorder;
    @Mock AiMetricRecorder metricRecorder;
    @InjectMocks AiDailyServiceImpl service;

    @ParameterizedTest @ValueSource(ints={200,201})
    void careSourcesMarkTruncationOnlyAboveTheLimit(int count) {
        List<CareNote> sources=new ArrayList<>();
        for(long id=1;id<=count;id++) {
            CareNote note=new CareNote(); note.setId(id); note.setElderId(4L); note.setFollowUp("待复核");
            sources.add(note);
        }
        when(careNoteMapper.selectList(any())).thenReturn(sources);
        DailyCareOverviewVo result=(DailyCareOverviewVo)service.overview(new Date()).getData();
        assertThat(result.getItems()).hasSize(Math.min(count,200));
        assertThat(result.getAttentionCount()).isEqualTo(count);
        assertThat(result.getTruncated()).isEqualTo(count>200);
    }
    @Test void serviceSourceLimitIsReportedEvenWhenTotalDisplayItemsAreBelow200() {
        List<NurseReserve> sources=new ArrayList<>();
        for(long id=1;id<=50;id++) {
            NurseReserve reserve=new NurseReserve(); reserve.setId(id); reserve.setElderId(4L);
            reserve.setServiceName("待执行预约"); sources.add(reserve);
        }
        when(nurseReserveMapper.selectCount(any())).thenReturn(51L);
        when(nurseReserveMapper.selectList(any())).thenReturn(sources);
        DailyCareOverviewVo result=(DailyCareOverviewVo)service.overview(new Date()).getData();
        assertThat(result.getItems()).hasSize(50);
        assertThat(result.getAttentionCount()).isEqualTo(51);
        assertThat(result.getTruncated()).isTrue();
    }
}
