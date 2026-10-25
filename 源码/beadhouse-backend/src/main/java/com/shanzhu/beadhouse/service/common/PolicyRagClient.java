package com.shanzhu.beadhouse.service.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanzhu.beadhouse.entity.po.PolicyRagSync;
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

    public void syncDocument(PolicyRagSync claim) throws Exception {
        JsonNode payload=PolicyRagPayload.parse(claim.getPayloadJson());
        if(!matches(payload,claim) || !claim.getRevision().equals(PolicyRagPayload.canonicalPayloadHash(payload)))
            throw new IllegalStateException("制度同步快照身份不一致");
        String response=client().postForObject(url("/v2/documents/sync"),request(payload),String.class);
        JsonNode receipt=objectMapper.readTree(response);
        if(receipt==null || !matches(receipt,claim) || !receipt.path("applied").isBoolean() || !receipt.path("applied").booleanValue())
            throw new IllegalStateException("制度同步服务未确认对应版本");
    }

    private boolean matches(JsonNode value,PolicyRagSync claim) {
        return value!=null && value.path("document_key").isTextual() && value.path("action").isTextual()
            && value.path("payload_hash").isTextual() && value.path("version").isIntegralNumber() && value.path("version").canConvertToLong()
            && claim.getDesiredVersion()!=null && claim.getDesiredVersion()>0
            && value.path("version").longValue()==claim.getDesiredVersion()
            && claim.getDocumentKey().equals(value.path("document_key").asText())
            && claim.getDesiredAction().equals(value.path("action").asText())
            && claim.getRevision().equals(value.path("payload_hash").asText());
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

    private HttpEntity<Object> request(Object body) {
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
