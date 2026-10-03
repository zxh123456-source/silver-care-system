package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanzhu.beadhouse.dao.mapper.PolicyDocMapper;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.PolicyDoc;
import com.shanzhu.beadhouse.entity.po.PolicyRagSync;
import com.shanzhu.beadhouse.entity.query.PolicyImportQuery;
import com.shanzhu.beadhouse.entity.query.PolicyQuery;
import com.shanzhu.beadhouse.entity.vo.PolicyAnswerVo;
import com.shanzhu.beadhouse.entity.vo.PolicyCitationVo;
import com.shanzhu.beadhouse.entity.vo.PolicyCatalogVo;
import com.shanzhu.beadhouse.entity.vo.PolicyRagHitVo;
import com.shanzhu.beadhouse.entity.vo.PolicyRagSearchVo;
import com.shanzhu.beadhouse.service.AiPolicyService;
import com.shanzhu.beadhouse.service.common.AiAuditRecorder;
import com.shanzhu.beadhouse.service.common.PolicyRagClient;
import com.shanzhu.beadhouse.service.common.PolicyRagSyncService;
import com.shanzhu.beadhouse.service.common.AiMetricRecorder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;

@Slf4j
@Service
public class AiPolicyServiceImpl implements AiPolicyService {
    private static final int MAX_CHUNK_LENGTH = 1000;

    @Resource
    private PolicyDocMapper policyDocMapper;
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
    @Resource
    private AiAuditRecorder auditRecorder;
    @Resource
    private PolicyRagClient ragClient;
    @Resource
    private PolicyRagSyncService ragSyncService;
    @Resource
    private AiMetricRecorder metricRecorder;

    @Override
    @Transactional
    public Result importPolicy(PolicyImportQuery query) {
        if (query == null || blank(query.getTitle()) || blank(query.getSource()) || blank(query.getContent())) {
            return Result.error(400, "制度标题、来源和正文不能为空");
        }
        int replaced = policyDocMapper.delete(new QueryWrapper<PolicyDoc>()
                .eq("title", query.getTitle().trim())
                .eq("source", query.getSource().trim()));
        List<String> chunks = splitChunks(query.getContent());
        List<PolicyDoc> insertedDocs = new ArrayList<>();
        int sectionNo = 1;
        for (String chunk : chunks) {
            PolicyDoc doc = new PolicyDoc();
            doc.setTitle(query.getTitle().trim());
            doc.setSource(query.getSource().trim());
            doc.setSectionNo(sectionNo++);
            doc.setContent(chunk);
            doc.setKeywords(String.join(" ", extractTerms(chunk)));
            policyDocMapper.insert(doc);
            insertedDocs.add(doc);
        }
        String documentKey = ragSyncService.enqueueUpsert(query.getTitle().trim(), query.getSource().trim(), insertedDocs);
        auditRecorder.record("制度知识库", "导入制度", "policy_doc", null,
                "片段数=" + chunks.size() + "，替换旧片段=" + replaced + "，文档键=" + shortKey(documentKey));
        return Result.success("制度已导入，共生成 " + chunks.size() + " 个检索片段", chunks.size());
    }

    @Override
    public Result query(PolicyQuery query) {
        long startedAt = System.currentTimeMillis();
        if (query == null || blank(query.getQuestion())) {
            return Result.error(400, "问题不能为空");
        }
        String question = query.getQuestion().trim();
        String retrievalBackend = "local-keyword";
        String fallbackCode = null;
        boolean securityBlocked = false;
        List<PolicyCitationVo> citations = new ArrayList<>();
        if (ragClient.isEnabled()) {
            try {
                PolicyRagSearchVo remote = ragClient.search(question);
                citations = rehydrate(remote.getHits());
                if (!citations.isEmpty()) retrievalBackend = remote.getBackend();
                else fallbackCode = "EMPTY_OR_STALE";
                if (Boolean.TRUE.equals(remote.getDegraded())) {
                    fallbackCode = remote.getFallbackCode() == null ? "RAG_DEGRADED" : remote.getFallbackCode();
                }
                if ("PROMPT_INJECTION_BLOCKED".equals(remote.getFallbackCode())) {
                    fallbackCode = "PROMPT_INJECTION_BLOCKED";
                    securityBlocked = true;
                    retrievalBackend = remote.getBackend();
                }
            } catch (Exception exception) {
                fallbackCode = exception.getClass().getSimpleName();
                log.warn("Policy RAG query failed; local fallback active: {}", fallbackCode);
            }
        }
        if (citations.isEmpty() && !securityBlocked) citations = localCitations(question);
        PolicyAnswerVo answer = new PolicyAnswerVo();
        answer.setQuestion(question);
        answer.setGrounded(!citations.isEmpty());
        answer.setCitations(citations);
        answer.setRetrievalBackend(retrievalBackend);
        String modelAnswer = citations.isEmpty() ? null : providerAnswer(question, citations);
        answer.setAnswer(citations.isEmpty() ? "知识库中暂未找到与该问题直接匹配的制度内容，请联系护理部确认。"
                : modelAnswer == null ? buildAnswer(citations) : modelAnswer);
        answer.setModelUsed(modelAnswer != null);
        AiMetricRecorder.Outcome metricOutcome = citations.isEmpty() ? AiMetricRecorder.Outcome.NO_HIT
                : (fallbackCode != null || (providerEnabled && modelAnswer == null)
                ? AiMetricRecorder.Outcome.FALLBACK : AiMetricRecorder.Outcome.SUCCESS);
        metricRecorder.record(AiMetricRecorder.Feature.POLICY_QUERY, AiMetricRecorder.Stage.PIPELINE,
                metricOutcome, retrievalBackend, modelAnswer != null, !citations.isEmpty(),
                System.currentTimeMillis() - startedAt, 1, citations.size(), fallbackCode,
                null);
        auditRecorder.record("制度知识库", "制度问答", "policy_query", null,
                "检索=" + retrievalBackend + "，命中片段=" + citations.size() + "，回退="
                        + (fallbackCode == null ? "NONE" : fallbackCode) + "，模型生成=" + (modelAnswer != null));
        return Result.success(answer);
    }

    @Override
    public Result listDocuments() {
        List<PolicyDoc> docs = policyDocMapper.selectList(new QueryWrapper<PolicyDoc>().orderByAsc("title", "section_no"));
        Map<String, PolicyCatalogVo> catalog = new LinkedHashMap<>();
        for (PolicyDoc doc : docs) {
            String key = doc.getTitle() + "\u0000" + doc.getSource();
            PolicyCatalogVo item = catalog.get(key);
            if (item == null) {
                item = new PolicyCatalogVo();
                item.setTitle(doc.getTitle());
                item.setSource(doc.getSource());
                item.setSectionCount(0);
                catalog.put(key, item);
            }
            item.setSectionCount(item.getSectionCount() + 1);
        }
        for (PolicyCatalogVo item : catalog.values()) {
            PolicyRagSync sync = ragSyncService.find(ragSyncService.documentKey(item.getTitle(), item.getSource()));
            item.setRagStatus(sync == null ? "NOT_SYNCED" : sync.getStatus());
            item.setRagAttemptCount(sync == null ? 0 : sync.getAttemptCount());
            item.setRagLastErrorCode(sync == null ? null : sync.getLastErrorCode());
        }
        return Result.success(new ArrayList<>(catalog.values()));
    }

    @Override
    @Transactional
    public Result deleteDocument(String title, String source) {
        if (blank(title) || blank(source)) return Result.error(400, "制度标题和来源不能为空");
        int count = policyDocMapper.delete(new QueryWrapper<PolicyDoc>().eq("title", title).eq("source", source));
        String documentKey = ragSyncService.enqueueDelete(title, source);
        auditRecorder.record("制度知识库", "删除制度", "policy_doc", null,
                "删除片段=" + count + "，文档键=" + shortKey(documentKey));
        return Result.success("已删除 " + count + " 个制度片段");
    }

    @Override
    public Result reindex() {
        int documents = ragSyncService.reindexAll();
        auditRecorder.record("制度知识库", "重建混合检索索引", "policy_rag_sync", null,
                "文档数=" + documents);
        return Result.success("已提交 " + documents + " 份制度进行索引同步", documents);
    }

    private String providerAnswer(String question, List<PolicyCitationVo> citations) {
        if (!providerEnabled || blank(providerUrl) || blank(providerApiKey)) return null;
        try {
            StringBuilder context = new StringBuilder();
            for (PolicyCitationVo item : citations) {
                context.append("[制度=").append(item.getTitle())
                        .append("；来源=").append(item.getSource())
                        .append("；片段=").append(item.getSectionNo()).append("] ")
                        .append(item.getContent()).append("\n");
            }
            Map<String, Object> body = new HashMap<>();
            body.put("model", providerModel);
            body.put("temperature", 0);
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> system = new HashMap<>();
            system.put("role", "system");
            system.put("content", "你是养老院制度问答助手。只能根据给定制度片段回答，不能补充片段之外的事实。回答要简洁，保留制度名称和片段编号作为引用；涉及医疗判断时提醒联系医护人员。");
            messages.add(system);
            Map<String, String> user = new HashMap<>();
            user.put("role", "user");
            user.put("content", "问题：" + question + "\n制度片段：\n" + context);
            messages.add(user);
            body.put("messages", messages);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(providerApiKey);
            String response = providerClient().postForObject(providerUrl, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            return blank(content) ? null : content.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildAnswer(List<PolicyCitationVo> citations) {
        StringBuilder answer = new StringBuilder("根据院内制度检索结果：");
        for (int i = 0; i < citations.size(); i++) {
            PolicyCitationVo doc = citations.get(i);
            if (i > 0) answer.append("；");
            answer.append("《").append(doc.getTitle()).append("》第 ")
                    .append(doc.getSectionNo()).append(" 段：").append(doc.getContent());
        }
        answer.append("。请以引用制度原文为准，涉及医疗判断时联系医护人员。");
        return answer.toString();
    }

    private List<PolicyCitationVo> localCitations(String question) {
        Set<String> terms = extractTerms(question);
        List<PolicyDoc> docs = policyDocMapper.selectList(new QueryWrapper<PolicyDoc>().orderByAsc("title", "section_no"));
        List<ScoredDoc> scored = docs.stream()
                .map(doc -> new ScoredDoc(doc, score(doc, terms)))
                .filter(item -> item.score > 0)
                .sorted(Comparator.comparingInt((ScoredDoc item) -> item.score).reversed())
                .limit(5)
                .collect(Collectors.toList());
        List<PolicyCitationVo> citations = new ArrayList<>();
        for (ScoredDoc item : scored) citations.add(toCitation(item.doc, (double) item.score));
        return citations;
    }

    private List<PolicyCitationVo> rehydrate(List<PolicyRagHitVo> hits) {
        if (hits == null || hits.isEmpty()) return new ArrayList<>();
        List<Long> ids = hits.stream().map(PolicyRagHitVo::getChunkId).distinct().collect(Collectors.toList());
        Map<Long, PolicyDoc> docs = policyDocMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(PolicyDoc::getId, item -> item));
        List<PolicyCitationVo> citations = new ArrayList<>();
        for (PolicyRagHitVo hit : hits) {
            PolicyDoc doc = docs.get(hit.getChunkId());
            if (doc != null) citations.add(toCitation(doc, hit.getScore()));
        }
        return citations;
    }

    private PolicyCitationVo toCitation(PolicyDoc doc, Double score) {
        PolicyCitationVo citation = new PolicyCitationVo();
        citation.setId(doc.getId());
        citation.setTitle(doc.getTitle());
        citation.setSource(doc.getSource());
        citation.setSectionNo(doc.getSectionNo());
        citation.setContent(doc.getContent());
        citation.setScore(score);
        return citation;
    }

    private int score(PolicyDoc doc, Set<String> terms) {
        String content = (doc.getContent() + " " + nullToEmpty(doc.getKeywords())).toLowerCase();
        int score = 0;
        for (String term : terms) {
            if (content.contains(term.toLowerCase())) score++;
        }
        return score;
    }

    private List<String> splitChunks(String content) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = content.replace("\r", "").split("\\n\\s*\\n");
        for (String paragraph : paragraphs) {
            String text = paragraph.trim();
            if (text.isEmpty()) continue;
            for (int start = 0; start < text.length(); start += MAX_CHUNK_LENGTH) {
                chunks.add(text.substring(start, Math.min(start + MAX_CHUNK_LENGTH, text.length())));
            }
        }
        return chunks;
    }

    private Set<String> extractTerms(String text) {
        Set<String> terms = new HashSet<>();
        String normalized = text.replaceAll("[，。！？、；：,.!?;:]", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) terms.add(token.toLowerCase());
        }
        String compact = normalized.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < compact.length(); i++) {
            terms.add(compact.substring(i, i + 2).toLowerCase());
        }
        return terms;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String shortKey(String value) {
        return value == null || value.length() <= 12 ? value : value.substring(0, 12);
    }

    private RestTemplate providerClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(providerTimeoutMs);
        factory.setReadTimeout(providerTimeoutMs);
        return new RestTemplate(factory);
    }

    private static class ScoredDoc {
        private final PolicyDoc doc;
        private final int score;

        private ScoredDoc(PolicyDoc doc, int score) {
            this.doc = doc;
            this.score = score;
        }
    }
}
