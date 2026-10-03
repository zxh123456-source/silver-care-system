package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanzhu.beadhouse.dao.mapper.CareNoteMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.CareNote;
import com.shanzhu.beadhouse.entity.query.AiCareNoteDraftQuery;
import com.shanzhu.beadhouse.entity.query.SaveCareNoteQuery;
import com.shanzhu.beadhouse.entity.query.FamilyReportQuery;
import com.shanzhu.beadhouse.entity.vo.AiCareNoteDraftVo;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.service.AiCareService;
import com.shanzhu.beadhouse.service.common.AiAuditRecorder;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
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
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiCareServiceImpl implements AiCareService {
    @Resource
    private CareNoteMapper careNoteMapper;
    @Resource
    private ElderMapper elderMapper;
    @Resource
    private com.shanzhu.beadhouse.dao.mapper.StaffMapper staffMapper;
    @Resource
    private AuthorityAssert authorityAssert;
    @Resource
    private AiAuditRecorder auditRecorder;
    @Resource
    private AiDataScopeService dataScopeService;
    @Resource
    private AiMetricRecorder metricRecorder;
    @Resource
    private ObjectMapper objectMapper;

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
    public Result draft(AiCareNoteDraftQuery query) {
        long startedAt = System.currentTimeMillis();
        if (query == null || query.getElderId() == null || blank(query.getSourceText())) {
            return Result.error(400, "老人编号和护理员原始记录不能为空");
        }
        dataScopeService.assertElderAccess(query.getElderId());
        AiCareNoteDraftVo draft = providerEnabled ? providerDraft(query) : null;
        AiCareNoteDraftVo result = draft == null ? ruleBasedDraft(query) : draft;
        auditRecorder.record("护理工作台", "生成护理草稿", "elder", query.getElderId(),
                "模型生成=" + result.isModelUsed());
        metricRecorder.record(AiMetricRecorder.Feature.CARE_DRAFT, AiMetricRecorder.Stage.PIPELINE,
                providerEnabled && !result.isModelUsed() ? AiMetricRecorder.Outcome.FALLBACK : AiMetricRecorder.Outcome.SUCCESS,
                result.isModelUsed() ? "MODEL" : "LOCAL", result.isModelUsed(), null,
                System.currentTimeMillis() - startedAt, 1, 1,
                providerEnabled && !result.isModelUsed() ? "MODEL_FALLBACK" : null, null);
        return Result.success(result);
    }

    @Override
    public Result save(SaveCareNoteQuery query) {
        if (query == null || query.getElderId() == null || blank(query.getSourceText())) {
            return Result.error(400, "老人编号和原始记录不能为空");
        }
        dataScopeService.assertElderAccess(query.getElderId());
        CareNote note = new CareNote();
        note.setElderId(query.getElderId());
        Long currentStaffId = authorityAssert.getLoginUserInfo() == null ? null : authorityAssert.getLoginUserId();
        note.setStaffId(currentStaffId);
        note.setEventTime(query.getEventTime() == null ? new Date() : query.getEventTime());
        note.setSourceText(query.getSourceText());
        note.setObservation(query.getObservation());
        note.setActionTaken(query.getActionTaken());
        note.setFollowUp(query.getFollowUp());
        note.setAppetite(query.getAppetite());
        note.setSleep(query.getSleep());
        note.setMedicine(query.getMedicine());
        note.setActivity(query.getActivity());
        note.setStatus(blank(query.getFollowUp()) ? "DONE" : "PENDING");
        note.setAiGenerated("Y");
        careNoteMapper.insert(note);
        auditRecorder.record("护理工作台", "确认护理记录", "care_note", note.getId(), null);
        return Result.success(note);
    }

    @Override
    public Result update(SaveCareNoteQuery query) {
        if (query == null || query.getId() == null || query.getElderId() == null || blank(query.getSourceText())) {
            return Result.error(400, "记录编号、老人编号和原始记录不能为空");
        }
        CareNote note = careNoteMapper.selectById(query.getId());
        AssertUtil.notNull(note, ExceptionEnum.DATA_NOT_EXIST);
        dataScopeService.assertElderAccess(note.getElderId());
        if (!Objects.equals(note.getElderId(), query.getElderId())) return Result.error(400, "护理记录不能更换老人");
        String previousFollowUp = note.getFollowUp();
        note.setElderId(query.getElderId());
        note.setSourceText(query.getSourceText());
        note.setObservation(query.getObservation());
        note.setActionTaken(query.getActionTaken());
        note.setFollowUp(query.getFollowUp());
        note.setAppetite(query.getAppetite());
        note.setSleep(query.getSleep());
        note.setMedicine(query.getMedicine());
        note.setActivity(query.getActivity());
        if (!Objects.equals(previousFollowUp, query.getFollowUp())) {
            note.setStatus(blank(query.getFollowUp()) ? "DONE" : "PENDING");
        }
        careNoteMapper.updateById(note);
        auditRecorder.record("护理工作台", "修改护理记录", "care_note", note.getId(), null);
        return Result.success(note);
    }

    @Override
    public Result handover(Date date) {
        long startedAt = System.currentTimeMillis();
        Date day = date == null ? new Date() : date;
        Calendar start = Calendar.getInstance();
        start.setTime(day);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DATE, 1);
        QueryWrapper<CareNote> noteQuery = new QueryWrapper<CareNote>()
                .ge("event_time", start.getTime())
                .lt("event_time", end.getTime())
                .orderByAsc("event_time");
        if (!dataScopeService.isAdmin()) {
            Set<Long> assignedIds = dataScopeService.currentAssignedElderIds();
            if (assignedIds.isEmpty()) noteQuery.eq("elder_id", -1L);
            else noteQuery.in("elder_id", assignedIds);
        }
        List<CareNote> notes = careNoteMapper.selectList(noteQuery);
        List<Long> elderIds = notes.stream().map(CareNote::getElderId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (!elderIds.isEmpty()) {
            Map<Long, String> elderNames = elderMapper.selectBatchIds(elderIds).stream()
                    .collect(Collectors.toMap(item -> item.getId(), item -> item.getName()));
            notes.forEach(note -> note.setElderName(elderNames.get(note.getElderId())));
        }
        List<Long> staffIds = notes.stream().map(CareNote::getStaffId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (!staffIds.isEmpty()) {
            Map<Long, String> staffNames = staffMapper.selectBatchIds(staffIds).stream()
                    .collect(Collectors.toMap(item -> item.getId(), item -> item.getName()));
            notes.forEach(note -> note.setStaffName(staffNames.get(note.getStaffId())));
        }
        List<String> followUps = new ArrayList<>();
        for (CareNote note : notes) {
            if (!"DONE".equals(note.getStatus()) && !blank(note.getFollowUp())) {
                followUps.add(note.getFollowUp());
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("date", new SimpleDateFormat("yyyy-MM-dd").format(day));
        data.put("count", notes.size());
        data.put("notes", notes);
        data.put("followUps", followUps);
        long completedCount = notes.stream().filter(note -> "DONE".equals(note.getStatus())).count();
        data.put("completedCount", completedCount);
        data.put("pendingCount", followUps.size());
        String modelSummary = providerEnabled ? providerHandoverSummary(notes) : null;
        data.put("summary", modelSummary == null ? buildSummary(notes, followUps) : modelSummary);
        data.put("modelUsed", modelSummary != null);
        auditRecorder.record("护理工作台", "生成交班摘要", "handover", null,
                "日期=" + data.get("date") + "，记录数=" + notes.size() + "，模型生成=" + (modelSummary != null));
        metricRecorder.record(AiMetricRecorder.Feature.CARE_HANDOVER, AiMetricRecorder.Stage.PIPELINE,
                providerEnabled && modelSummary == null ? AiMetricRecorder.Outcome.FALLBACK : AiMetricRecorder.Outcome.SUCCESS,
                modelSummary == null ? "LOCAL" : "MODEL", modelSummary != null, null,
                System.currentTimeMillis() - startedAt, notes.size(), followUps.size(),
                providerEnabled && modelSummary == null ? "MODEL_FALLBACK" : null, null);
        return Result.success(data);
    }

    @Override
    public Result complete(Long id) {
        CareNote note = careNoteMapper.selectById(id);
        AssertUtil.notNull(note, ExceptionEnum.DATA_NOT_EXIST);
        dataScopeService.assertElderAccess(note.getElderId());
        note.setStatus("DONE");
        careNoteMapper.updateById(note);
        auditRecorder.record("护理工作台", "完成待跟进", "care_note", note.getId(), null);
        return Result.success(note);
    }

    @Override
    public Result familyReport(FamilyReportQuery query) {
        long startedAt = System.currentTimeMillis();
        if (query == null || query.getElderId() == null || query.getStartDate() == null || query.getEndDate() == null) {
            return Result.error(400, "老人和报告日期范围不能为空");
        }
        dataScopeService.assertElderAccess(query.getElderId());
        Calendar end = Calendar.getInstance();
        end.setTime(query.getEndDate());
        end.add(Calendar.DATE, 1);
        List<CareNote> notes = careNoteMapper.selectList(new QueryWrapper<CareNote>()
                .eq("elder_id", query.getElderId())
                .ge("event_time", query.getStartDate())
                .lt("event_time", end.getTime())
                .orderByAsc("event_time"));
        Map<String, Object> data = new HashMap<>();
        data.put("elderId", query.getElderId());
        data.put("startDate", new SimpleDateFormat("yyyy-MM-dd").format(query.getStartDate()));
        data.put("endDate", new SimpleDateFormat("yyyy-MM-dd").format(query.getEndDate()));
        data.put("sourceCount", notes.size());
        String summary = providerEnabled ? providerFamilyReport(notes) : null;
        data.put("summary", summary == null ? buildFamilyReport(notes) : summary);
        data.put("modelUsed", summary != null);
        data.put("warning", "这是基于护理记录生成的沟通草稿，请工作人员核对后再发送，不包含医疗诊断。");
        auditRecorder.record("护理工作台", "生成家属周报", "elder", query.getElderId(),
                "记录数=" + notes.size() + "，模型生成=" + (summary != null));
        metricRecorder.record(AiMetricRecorder.Feature.CARE_FAMILY, AiMetricRecorder.Stage.PIPELINE,
                notes.isEmpty() ? AiMetricRecorder.Outcome.NO_HIT
                        : providerEnabled && summary == null ? AiMetricRecorder.Outcome.FALLBACK : AiMetricRecorder.Outcome.SUCCESS,
                summary == null ? "LOCAL" : "MODEL", summary != null, null,
                System.currentTimeMillis() - startedAt, notes.size(), 1,
                providerEnabled && summary == null && !notes.isEmpty() ? "MODEL_FALLBACK" : null, null);
        return Result.success(data);
    }

    private AiCareNoteDraftVo ruleBasedDraft(AiCareNoteDraftQuery query) {
        String text = query.getSourceText().trim();
        AiCareNoteDraftVo draft = new AiCareNoteDraftVo();
        draft.setElderId(query.getElderId());
        draft.setSourceText(text);
        draft.setObservation(text);
        draft.setActivity(containsAny(text, "活动", "锻炼", "散步") ? text : null);
        draft.setSleep(containsAny(text, "睡不好", "没睡好", "失眠") ? "老人反馈睡眠不佳，需继续观察" : null);
        draft.setAppetite(containsAny(text, "没吃", "食欲", "进餐") ? text : null);
        draft.setMedicine(containsAny(text, "服药", "吃药", "用药") ? text : null);
        draft.setActionTaken(extractAfter(text, "已", "已经"));
        draft.setFollowUp(containsAny(text, "明天", "后续", "跟进", "再问") ? text : "请护理员补充后续跟进事项");
        draft.setModelUsed(false);
        draft.setWarning("当前使用本地规则解析，请护理员确认字段后再保存；系统不会自动作出医疗判断。");
        return draft;
    }

    private AiCareNoteDraftVo providerDraft(AiCareNoteDraftQuery query) {
        if (blank(providerUrl) || blank(providerApiKey)) return null;
        try {
            RestTemplate restTemplate = providerClient();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(providerApiKey);
            Map<String, Object> body = new HashMap<>();
            body.put("model", providerModel);
            body.put("temperature", 0);
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> system = new HashMap<>();
            system.put("role", "system");
            system.put("content", "将护理员口述转为JSON，只允许 observation, actionTaken, followUp, appetite, sleep, medicine, activity 字段，不要医疗诊断，不要编造未提及事实。");
            messages.add(system);
            Map<String, String> user = new HashMap<>();
            user.put("role", "user");
            user.put("content", query.getSourceText());
            messages.add(user);
            body.put("messages", messages);
            String response = restTemplate.postForObject(providerUrl, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            JsonNode json = objectMapper.readTree(content.replace("```json", "").replace("```", "").trim());
            AiCareNoteDraftVo draft = objectMapper.treeToValue(json, AiCareNoteDraftVo.class);
            draft.setElderId(query.getElderId());
            draft.setSourceText(query.getSourceText());
            draft.setModelUsed(true);
            draft.setWarning("模型结果仅作记录草稿，请核对原始记录后保存。");
            return draft;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildSummary(List<CareNote> notes, List<String> followUps) {
        if (notes.isEmpty()) return "今天暂无已确认的护理记录。";
        StringBuilder summary = new StringBuilder("今日共 ").append(notes.size()).append(" 条护理记录。 ");
        if (followUps.isEmpty()) return summary.append("当前记录中未填写待跟进事项。").toString();
        return summary.append("待跟进 ").append(followUps.size()).append(" 项：")
                .append(String.join("；", followUps)).toString();
    }

    private String providerHandoverSummary(List<CareNote> notes) {
        if (blank(providerUrl) || blank(providerApiKey) || notes.isEmpty()) return null;
        try {
            StringBuilder context = new StringBuilder();
            for (CareNote note : notes) {
                context.append("老人:").append(note.getElderName() == null ? note.getElderId() : note.getElderName())
                        .append(";记录人:").append(note.getStaffName() == null ? "未记录" : note.getStaffName())
                        .append(";观察:").append(nullToEmpty(note.getObservation()))
                        .append(";措施:").append(nullToEmpty(note.getActionTaken()))
                        .append(";待跟进:").append(nullToEmpty(note.getFollowUp()))
                        .append(";状态:").append(nullToEmpty(note.getStatus())).append("\n");
            }
            RestTemplate restTemplate = providerClient();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(providerApiKey);
            Map<String, Object> body = new HashMap<>();
            body.put("model", providerModel);
            body.put("temperature", 0);
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> system = new HashMap<>();
            system.put("role", "system");
            system.put("content", "根据护理记录生成简洁的中文交班摘要。只总结输入事实，按老人归纳观察、已采取措施和待跟进事项，不做医疗诊断，不补充未提及信息。");
            messages.add(system);
            Map<String, String> user = new HashMap<>();
            user.put("role", "user");
            user.put("content", context.toString());
            messages.add(user);
            body.put("messages", messages);
            String response = restTemplate.postForObject(providerUrl, new HttpEntity<>(body, headers), String.class);
            String content = objectMapper.readTree(response).path("choices").path(0).path("message").path("content").asText();
            return blank(content) ? null : content.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    protected String providerFamilyReport(List<CareNote> notes) {
        if (blank(providerUrl) || blank(providerApiKey) || notes.isEmpty()) return null;
        try {
            StringBuilder context = new StringBuilder();
            for (CareNote note : notes) {
                context.append("观察:").append(nullToEmpty(note.getObservation()))
                        .append(";措施:").append(nullToEmpty(note.getActionTaken()))
                        .append(";进餐:").append(nullToEmpty(note.getAppetite()))
                        .append(";休息:").append(nullToEmpty(note.getSleep()))
                        .append(";活动:").append(nullToEmpty(note.getActivity()))
                        .append(";待跟进:").append(nullToEmpty(note.getFollowUp())).append("\n");
            }
            Map<String, Object> body = new HashMap<>();
            body.put("model", providerModel);
            body.put("temperature", 0);
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> system = new HashMap<>();
            system.put("role", "system");
            system.put("content", "根据护理记录生成一份面向家属的简洁中文周报草稿，语气温和客观，只陈述记录事实，不做医疗诊断，不夸大、不补充未提及内容。");
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

    private String buildFamilyReport(List<CareNote> notes) {
        if (notes.isEmpty()) return "该时间段暂无已确认的护理记录。";
        long followUpCount = notes.stream().filter(note -> !blank(note.getFollowUp())).count();
        return "本周期共记录 " + notes.size() + " 次护理情况，已记录日常观察、活动和护理措施；其中有 "
                + followUpCount + " 项需要工作人员继续跟进。具体内容请以院内护理记录为准。";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private RestTemplate providerClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(providerTimeoutMs);
        factory.setReadTimeout(providerTimeoutMs);
        return new RestTemplate(factory);
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String extractAfter(String text, String... prefixes) {
        for (String prefix : prefixes) {
            int index = text.indexOf(prefix);
            if (index >= 0 && index + prefix.length() < text.length()) return text.substring(index).trim();
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
