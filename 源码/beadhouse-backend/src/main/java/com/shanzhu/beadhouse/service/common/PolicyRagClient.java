package com.shanzhu.beadhouse.service.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanzhu.beadhouse.entity.po.PolicyDoc;
import com.shanzhu.beadhouse.entity.vo.PolicyRagHitVo;
import com.shanzhu.beadhouse.entity.vo.PolicyRagSearchVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PolicyRagClient {
    @Resource
    private ObjectMapper objectMapper;
    @Value("${ai.rag.enabled:true}")
    private boolean enabled;
    @Value("${ai.rag.url:http://127.0.0.1:8001}")
    private String baseUrl;
    @Value("${ai.rag.internal-token:}")
    private String internalToken;
    @Value("${ai.rag.connect-timeout-ms:500}")
    private int connectTimeoutMs;
    @Value("${ai.rag.read-timeout-ms:1500}")
    private int readTimeoutMs;
    @Value("${ai.rag.top-k:5}")
    private int topK;

    public boolean isEnabled() {
        return enabled;
    }

    public void indexDocument(String documentKey, String title, String source, List<PolicyDoc> docs) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("document_key", documentKey);
        body.put("title", title);
        body.put("source", source);
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (PolicyDoc doc : docs) {
            Map<String, Object> chunk = new HashMap<>();
            chunk.put("chunk_id", doc.getId());
            chunk.put("section_no", doc.getSectionNo());
            chunk.put("content", doc.getContent());
            chunks.add(chunk);
        }
        body.put("chunks", chunks);
        client().postForEntity(url("/v1/documents/index"), request(body), String.class);
    }

    public void deleteDocument(String documentKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("document_key", documentKey);
        client().postForEntity(url("/v1/documents/delete"), request(body), String.class);
    }

    public PolicyRagSearchVo search(String question) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("question", question);
        body.put("top_k", topK);
        String response = client().postForObject(url("/v1/query"), request(body), String.class);
        JsonNode root = objectMapper.readTree(response);
        PolicyRagSearchVo result = new PolicyRagSearchVo();
        result.setBackend(root.path("backend").asText("unknown"));
        result.setDegraded(root.path("degraded").asBoolean(false));
        result.setFallbackCode(root.path("fallback_code").isNull() ? null : root.path("fallback_code").asText(null));
        result.setEmbeddingBackend(root.path("embedding_backend").asText("unknown"));
        List<PolicyRagHitVo> hits = new ArrayList<>();
        for (JsonNode node : root.path("hits")) {
            if (node.path("chunk_id").canConvertToLong()) {
                hits.add(new PolicyRagHitVo(node.path("chunk_id").asLong(), node.path("score").asDouble()));
            }
        }
        result.setHits(hits);
        return result;
    }

    private HttpEntity<Map<String, Object>> request(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        return new HttpEntity<>(body, headers);
    }

    private RestTemplate client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    private String url(String path) {
        return baseUrl.replaceAll("/+$", "") + path;
    }
}
