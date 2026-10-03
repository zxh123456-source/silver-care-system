package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.dao.mapper.HealthDataMapper;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.Elder;
import com.shanzhu.beadhouse.entity.po.HealthData;
import com.shanzhu.beadhouse.entity.query.HealthMeasurementQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.HealthTrendVo;
import com.shanzhu.beadhouse.service.AiHealthService;
import com.shanzhu.beadhouse.service.common.AiAuditRecorder;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
import com.shanzhu.beadhouse.service.common.HealthChangeDetector;
import com.shanzhu.beadhouse.service.common.AiMetricRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiHealthServiceImpl implements AiHealthService {
    @Resource
    private HealthDataMapper healthDataMapper;
    @Resource
    private ElderMapper elderMapper;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private AiAuditRecorder auditRecorder;
    @Resource
    private AiDataScopeService dataScopeService;
    @Resource
    private HealthChangeDetector healthChangeDetector;
    @Resource
    private AiMetricRecorder metricRecorder;

    @Value("${ai.provider.enabled:false}")
    private boolean providerEnabled;
    @Value("${ai.provider.url:}")
    private String providerUrl;
    @Value("${ai.provider.api-key:}")
    private String providerApiKey;
    @Value("${ai.provider.model:}")
    private String providerModel;
    @Value("${ai.provider.timeout-ms:8000}")
    private int providerTimeoutMs;

    @Override
    public Result addMeasurement(HealthMeasurementQuery query) {
        if (query == null || query.getElderId() == null) {
            return Result.error(400, "请选择老人");
        }
        dataScopeService.assertElderAccess(query.getElderId());
        if (elderMapper.selectById(query.getElderId()) == null) {
            return Result.error(400, "老人档案不存在");
        }
        if (allMetricsEmpty(query)) {
            return Result.error(400, "至少填写一项健康指标");
        }
        String validation = validate(query);
        if (validation != null) return Result.error(400, validation);

        HealthData data = new HealthData();
        data.setElderId(query.getElderId());
        data.setMeasureTime(query.getMeasureTime() == null ? new Date() : query.getMeasureTime());
        data.setWeight(query.getWeight());
        data.setTemperature(query.getTemperature());
        data.setHeartRate(query.getHeartRate());
        data.setSystolicBloodPressure(query.getSystolicBloodPressure());
        data.setDiastolicBloodPressure(query.getDiastolicBloodPressure());
        data.setBloodOxygenSaturation(query.getBloodOxygenSaturation());
        data.setFastingBloodGlucose(query.getFastingBloodGlucose());
        data.setPostprandialBloodGlucose(query.getPostprandialBloodGlucose());
        data.setRemarks(query.getRemarks());
        healthDataMapper.insert(data);
        auditRecorder.record("健康趋势", "新增健康测量", "health_data", data.getId(), null);
        return Result.success(data);
    }

    @Override
    public Result trend(Long elderId, Integer limit) {
        long startedAt = System.currentTimeMillis();
        if (elderId == null) return Result.error(400, "请选择老人");
        dataScopeService.assertElderAccess(elderId);
        Elder elder = elderMapper.selectById(elderId);
        if (elder == null) return Result.error(400, "老人档案不存在");
        int safeLimit = limit == null ? 30 : Math.max(1, Math.min(limit, 90));
        List<HealthData> records = healthDataMapper.selectList(new QueryWrapper<HealthData>()
                .eq("elder_id", elderId)
                .orderByDesc("measure_time", "id")
                .last("LIMIT " + safeLimit));
        Collections.reverse(records);

        List<String> reminders = healthChangeDetector.detect(records);
        String modelSummary = providerSummary(elder.getName(), records, reminders);
        HealthTrendVo trend = new HealthTrendVo();
        trend.setElderId(elderId);
        trend.setElderName(elder.getName());
        trend.setRecordCount(records.size());
        trend.setRecords(records);
        trend.setChangeReminders(reminders);
        trend.setModelUsed(modelSummary != null);
        trend.setSummary(modelSummary == null ? localSummary(records, reminders) : modelSummary);
        auditRecorder.record("健康趋势", "生成趋势摘要", "elder", elderId,
                "记录数=" + records.size() + "，变化提醒=" + reminders.size() + "，模型生成=" + (modelSummary != null));
        metricRecorder.record(AiMetricRecorder.Feature.HEALTH_TREND, AiMetricRecorder.Stage.PIPELINE,
                records.isEmpty() ? AiMetricRecorder.Outcome.NO_HIT
                        : providerEnabled && modelSummary == null ? AiMetricRecorder.Outcome.FALLBACK : AiMetricRecorder.Outcome.SUCCESS,
                modelSummary == null ? "LOCAL" : "MODEL", modelSummary != null, null,
                System.currentTimeMillis() - startedAt, records.size(), reminders.size(),
                providerEnabled && modelSummary == null && !records.isEmpty() ? "MODEL_FALLBACK" : null, null);
        return Result.success(trend);
    }

    @Override
    public Result pageElders(PageSearchElderByKeyQuery query) {
        return dataScopeService.pageAccessibleElders(query);
    }

    private String localSummary(List<HealthData> records, List<String> reminders) {
        if (records.isEmpty()) return "暂无健康测量记录。";
        HealthData latest = records.get(records.size() - 1);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(measurementTime(latest));
        String suffix = reminders.isEmpty() ? "与上一条记录相比，暂未触发数据变化提醒。"
                : "检测到 " + reminders.size() + " 项数据变化：" + String.join("；", reminders) + "。";
        return "已记录 " + records.size() + " 次测量，最近一次为 " + time + "。" + suffix
                + "该摘要仅描述记录变化，不构成医疗诊断。";
    }

    private String providerSummary(String elderName, List<HealthData> records, List<String> reminders) {
        if (!providerEnabled || blank(providerUrl) || blank(providerApiKey) || records.isEmpty()) return null;
        try {
            StringBuilder context = new StringBuilder("老人：").append(elderName).append("\n");
            for (HealthData item : records) {
                context.append(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(measurementTime(item)));
                appendMetric(context, "体温", item.getTemperature());
                appendMetric(context, "心率", item.getHeartRate());
                appendMetric(context, "收缩压", item.getSystolicBloodPressure());
                appendMetric(context, "舒张压", item.getDiastolicBloodPressure());
                appendMetric(context, "血氧", item.getBloodOxygenSaturation());
                appendMetric(context, "体重", item.getWeight());
                appendMetric(context, "空腹血糖", item.getFastingBloodGlucose());
                appendMetric(context, "餐后血糖", item.getPostprandialBloodGlucose());
                context.append("\n");
            }
            context.append("数据变化提醒：").append(reminders);
            Map<String, Object> body = new HashMap<>();
            body.put("model", providerModel);
            body.put("temperature", 0);
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> system = new HashMap<>();
            system.put("role", "system");
            system.put("content", "根据健康测量记录生成简洁的中文趋势摘要。只描述数据和变化，不做诊断，不给出治疗或用药建议；提醒工作人员结合院内流程人工复核。");
            messages.add(system);
            Map<String, String> user = new HashMap<>();
            user.put("role", "user");
            user.put("content", context.toString());
            messages.add(user);
            body.put("messages", messages);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(providerApiKey);
            String response = providerClient().postForObject(providerUrl, new HttpEntity<>(body, headers), String.class);
            String content = objectMapper.readTree(response).path("choices").path(0).path("message").path("content").asText();
            return blank(content) ? null : content.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Date measurementTime(HealthData item) {
        if (item.getMeasureTime() != null) return item.getMeasureTime();
        if (item.getCreateTime() != null) return item.getCreateTime();
        return new Date(0);
    }

    private void appendMetric(StringBuilder target, String name, Number value) {
        if (value != null) target.append(" ").append(name).append("=").append(value);
    }

    private String validate(HealthMeasurementQuery query) {
        if (outOfRange(query.getWeight(), 20, 300)) return "体重应在 20-300kg 范围内";
        if (outOfRange(query.getTemperature(), 25, 45)) return "体温应在 25-45℃ 范围内";
        if (outOfRange(query.getHeartRate(), 20, 250)) return "心率应在 20-250 次/分范围内";
        if (outOfRange(query.getSystolicBloodPressure(), 40, 300)) return "收缩压应在 40-300mmHg 范围内";
        if (outOfRange(query.getDiastolicBloodPressure(), 30, 200)) return "舒张压应在 30-200mmHg 范围内";
        if (outOfRange(query.getBloodOxygenSaturation(), 50, 100)) return "血氧饱和度应在 50-100% 范围内";
        if (outOfRange(query.getFastingBloodGlucose(), 0, 50)) return "空腹血糖应在 0-50mmol/L 范围内";
        if (outOfRange(query.getPostprandialBloodGlucose(), 0, 50)) return "餐后血糖应在 0-50mmol/L 范围内";
        return null;
    }

    private boolean allMetricsEmpty(HealthMeasurementQuery query) {
        return query.getWeight() == null && query.getTemperature() == null && query.getHeartRate() == null
                && query.getSystolicBloodPressure() == null && query.getDiastolicBloodPressure() == null
                && query.getBloodOxygenSaturation() == null && query.getFastingBloodGlucose() == null
                && query.getPostprandialBloodGlucose() == null;
    }

    private boolean outOfRange(Number value, double min, double max) {
        return value != null && (value.doubleValue() < min || value.doubleValue() > max);
    }

    private RestTemplate providerClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(providerTimeoutMs);
        factory.setReadTimeout(providerTimeoutMs);
        return new RestTemplate(factory);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
