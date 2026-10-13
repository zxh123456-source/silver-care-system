package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.vo.DailyCareItemVo;
import com.shanzhu.beadhouse.service.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DailySourcePagingTest {
    @Mock CareNoteMapper careNoteMapper;
    @Mock MedicationPlanMapper medicationPlanMapper;
    @Mock MedicationExecutionMapper medicationExecutionMapper;
    @Mock HealthDataMapper healthDataMapper;
    @Mock NurseReserveMapper nurseReserveMapper;
    @Mock AiDataScopeService dataScopeService;
    @Mock HealthChangeDetector healthChangeDetector;
    @InjectMocks AiDailyServiceImpl service;

    @Test void allSourceKindsTraverseCursorsAndEmitAtMost100ItemsWithoutDisplayCaps() {
        List<CareNote> care=new ArrayList<>();
        List<MedicationPlan> plans=new ArrayList<>();
        List<NurseReserve> reserves=new ArrayList<>();
        List<HealthData> elders=new ArrayList<>(), histories=new ArrayList<>();
        for(long id=1;id<=201;id++) {
            CareNote note=new CareNote(); note.setId(id); note.setElderId(4L); care.add(note);
            if(id>101) continue;
            MedicationPlan plan=new MedicationPlan(); plan.setId(id); plan.setElderId(4L);
            plan.setMedicineName("测试"); plan.setDoseInstruction("人工核对"); plan.setPeriods("早,晚"); plans.add(plan);
            NurseReserve reserve=new NurseReserve(); reserve.setId(id); reserve.setElderId(4L); reserves.add(reserve);
            HealthData elder=new HealthData(); elder.setElderId(id); elders.add(elder);
            HealthData measured=new HealthData(); measured.setId(1000+id); measured.setElderId(id);
            measured.setMeasureTime(new Date()); histories.add(measured);
        }
        when(careNoteMapper.selectList(any())).thenReturn(care.subList(0,100),care.subList(100,200),care.subList(200,201));
        when(medicationPlanMapper.selectList(any())).thenReturn(plans.subList(0,100),plans.subList(100,101));
        when(nurseReserveMapper.selectList(any())).thenReturn(reserves.subList(0,100),reserves.subList(100,101));
        MedicationExecution done=new MedicationExecution(); done.setPlanId(1L); done.setPeriod("早"); done.setStatus("DONE");
        when(medicationExecutionMapper.selectList(any())).thenReturn(Collections.singletonList(done),Collections.emptyList());
        AtomicInteger elderPage=new AtomicInteger(), historyPage=new AtomicInteger();
        when(healthDataMapper.selectList(any())).thenAnswer(invocation -> {
            QueryWrapper<HealthData> query=invocation.getArgument(0);
            boolean first=("DISTINCT elder_id".equals(query.getSqlSelect()) ? elderPage : historyPage).getAndIncrement()==0;
            List<HealthData> data="DISTINCT elder_id".equals(query.getSqlSelect()) ? elders : histories;
            return first ? data.subList(0,100) : data.subList(100,101);
        });
        when(healthChangeDetector.detect(anyList())).thenReturn(Collections.singletonList("记录变化"));
        List<List<DailyCareItemVo>> pages=new ArrayList<>();
        service.forEachSourcePage(new Date(),pages::add);
        assertThat(pages).allSatisfy(page -> assertThat(page.size()).isBetween(1,100));
        List<DailyCareItemVo> items=pages.stream().flatMap(List::stream).collect(Collectors.toList());
        assertThat(items).hasSize(604);
        assertThat(items.stream().map(DailyCareItemVo::getKey).distinct().count()).isEqualTo(604);
        assertThat(items.stream().filter(item -> item.getCategory().equals("CARE_FOLLOW_UP")).count()).isEqualTo(201);
        assertThat(items.stream().filter(item -> item.getCategory().startsWith("MEDICATION_")).count()).isEqualTo(201);
        assertThat(items.stream().filter(item -> item.getCategory().equals("HEALTH_CHANGE")).count()).isEqualTo(101);
        assertThat(items.stream().filter(item -> item.getCategory().equals("SERVICE_BACKLOG")).count()).isEqualTo(101);
        assertThat(items.stream().map(DailyCareItemVo::getKey)).doesNotContain("med-1-早");
        ArgumentCaptor<QueryWrapper<CareNote>> queries=ArgumentCaptor.forClass((Class)QueryWrapper.class);
        verify(careNoteMapper,times(3)).selectList(queries.capture());
        List<Long> cursors=new ArrayList<>();
        for(QueryWrapper<CareNote> query:queries.getAllValues()) {
            assertThat(query.getSqlSegment()).contains("id >", "ORDER BY id ASC", "LIMIT 100");
            cursors.addAll(query.getParamNameValuePairs().values().stream().filter(Long.class::isInstance).map(Long.class::cast).collect(Collectors.toList()));
        }
        assertThat(cursors).containsExactly(0L,100L,200L);
        verify(dataScopeService,times(9)).applyElderScope(any(),eq("elder_id"));
        verify(healthChangeDetector,times(101)).detect(anyList());
    }
}
