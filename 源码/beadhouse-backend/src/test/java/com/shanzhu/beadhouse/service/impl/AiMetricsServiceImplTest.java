package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.shanzhu.beadhouse.dao.mapper.AiMetricEventMapper;
import com.shanzhu.beadhouse.dao.mapper.PolicyRagSyncMapper;
import com.shanzhu.beadhouse.entity.po.AiMetricEvent;
import com.shanzhu.beadhouse.entity.vo.AiMetricsSummaryVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMetricsServiceImplTest {
    @Mock private AiMetricEventMapper metricMapper;
    @Mock private PolicyRagSyncMapper syncMapper;
    @InjectMocks private AiMetricsServiceImpl service;

    @Test
    void calculatesPipelineRatesAndNearestRankP95() {
        when(metricMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(
                event("POLICY_QUERY", "SUCCESS", "MILVUS-HYBRID", "Y", "Y", 100L),
                event("POLICY_QUERY", "FALLBACK", "LOCAL-KEYWORD", "N", "Y", 200L),
                event("CARE_DRAFT", "ERROR", "MODEL", "N", null, 300L),
                event("DAILY_OVERVIEW", "DISABLED", "RULES", "N", null, 400L)
        ));
        when(syncMapper.selectCount(any(Wrapper.class))).thenReturn(2L, 1L);

        AiMetricsSummaryVo result = (AiMetricsSummaryVo) service.summary(7).getData();

        assertThat(result.getPipelineCalls()).isEqualTo(4);
        assertThat(result.getSuccessRate()).isEqualTo(75.0);
        assertThat(result.getFallbackRate()).isEqualTo(25.0);
        assertThat(result.getErrorRate()).isEqualTo(25.0);
        assertThat(result.getPolicyGroundedRate()).isEqualTo(100.0);
        assertThat(result.getModelUseRate()).isEqualTo(25.0);
        assertThat(result.getP95LatencyMs()).isEqualTo(400L);
        assertThat(result.getRagPending()).isEqualTo(2);
        assertThat(result.getRagFailed()).isEqualTo(1);
    }

    private AiMetricEvent event(String feature, String outcome, String backend, String modelUsed,
                                String grounded, Long duration) {
        AiMetricEvent event = new AiMetricEvent();
        event.setFeature(feature); event.setStage("PIPELINE"); event.setOutcome(outcome); event.setBackend(backend);
        event.setModelUsed(modelUsed); event.setGrounded(grounded); event.setDurationMs(duration); event.setCreateTime(new Date());
        return event;
    }
}
