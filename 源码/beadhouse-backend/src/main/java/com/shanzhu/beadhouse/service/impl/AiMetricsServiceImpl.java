package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.dao.mapper.AiMetricEventMapper;
import com.shanzhu.beadhouse.dao.mapper.PolicyRagSyncMapper;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.AiMetricEvent;
import com.shanzhu.beadhouse.entity.po.PolicyRagSync;
import com.shanzhu.beadhouse.entity.vo.AiMetricsSummaryVo;
import com.shanzhu.beadhouse.service.AiMetricsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AiMetricsServiceImpl implements AiMetricsService {
    @Resource private AiMetricEventMapper metricMapper;
    @Resource private PolicyRagSyncMapper ragSyncMapper;

    @Override
    public Result summary(Integer days) {
        int safeDays = days == null ? 7 : Math.max(1, Math.min(days, 31));
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.DATE, -(safeDays - 1));
        List<AiMetricEvent> pipeline = metricMapper.selectList(new QueryWrapper<AiMetricEvent>()
                .eq("stage", "PIPELINE").ge("create_time", start.getTime()).orderByAsc("create_time", "id"));
        int total = pipeline.size();
        long errors = count(pipeline, "ERROR");
        long fallbacks = count(pipeline, "FALLBACK");
        long modelUsed = pipeline.stream().filter(item -> "Y".equals(item.getModelUsed())).count();
        List<AiMetricEvent> policy = pipeline.stream().filter(item -> "POLICY_QUERY".equals(item.getFeature())).collect(Collectors.toList());
        long grounded = policy.stream().filter(item -> "Y".equals(item.getGrounded())).count();
        List<Long> durations = pipeline.stream().map(AiMetricEvent::getDurationMs).filter(value -> value != null).sorted().collect(Collectors.toList());

        Map<String, Map<String, Integer>> dailyMap = new LinkedHashMap<>();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        for (int offset = 0; offset < safeDays; offset++) {
            Calendar date = (Calendar) start.clone(); date.add(Calendar.DATE, offset);
            Map<String, Integer> value = new LinkedHashMap<>(); value.put("total", 0); value.put("fallback", 0); value.put("error", 0);
            dailyMap.put(formatter.format(date.getTime()), value);
        }
        for (AiMetricEvent event : pipeline) {
            Map<String, Integer> value = dailyMap.get(formatter.format(event.getCreateTime()));
            if (value == null) continue;
            value.put("total", value.get("total") + 1);
            if ("FALLBACK".equals(event.getOutcome())) value.put("fallback", value.get("fallback") + 1);
            if ("ERROR".equals(event.getOutcome())) value.put("error", value.get("error") + 1);
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : dailyMap.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("date", entry.getKey()); row.putAll(entry.getValue()); daily.add(row);
        }
        Map<String, Integer> byFeature = countBy(pipeline, true);
        Map<String, Integer> backends = countBy(pipeline, false);

        AiMetricsSummaryVo result = new AiMetricsSummaryVo();
        result.setDays(safeDays);
        result.setPipelineCalls(total);
        result.setSuccessRate(rate(total - errors, total));
        result.setFallbackRate(rate(fallbacks, total));
        result.setErrorRate(rate(errors, total));
        result.setPolicyGroundedRate(rate(grounded, policy.size()));
        result.setModelUseRate(rate(modelUsed, total));
        result.setP95LatencyMs(percentile95(durations));
        result.setRagPending(ragSyncMapper.selectCount(new QueryWrapper<PolicyRagSync>().eq("status", "PENDING")).intValue());
        result.setRagFailed(ragSyncMapper.selectCount(new QueryWrapper<PolicyRagSync>().eq("status", "FAILED")).intValue());
        result.setDaily(daily);
        result.setByFeature(byFeature);
        result.setRetrievalBackend(backends);
        return Result.success(result);
    }

    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupOldEvents() {
        Calendar cutoff = Calendar.getInstance(); cutoff.add(Calendar.DATE, -90);
        metricMapper.delete(new QueryWrapper<AiMetricEvent>().lt("create_time", cutoff.getTime()));
    }

    private long count(List<AiMetricEvent> events, String outcome) {
        return events.stream().filter(item -> outcome.equals(item.getOutcome())).count();
    }

    private Double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : Math.round((numerator * 10000.0 / denominator)) / 100.0;
    }

    private Long percentile95(List<Long> values) {
        if (values.isEmpty()) return 0L;
        int index = Math.max(0, (int) Math.ceil(values.size() * 0.95) - 1);
        return values.get(index);
    }

    private Map<String, Integer> countBy(List<AiMetricEvent> events, boolean feature) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (AiMetricEvent event : events) {
            String key = feature ? event.getFeature() : event.getBackend();
            if (key == null) continue;
            result.put(key, result.getOrDefault(key, 0) + 1);
        }
        return result;
    }
}
