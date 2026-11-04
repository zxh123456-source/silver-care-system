package com.shanzhu.beadhouse.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shanzhu.beadhouse.dao.mapper.CareNoteMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.CareNote;
import com.shanzhu.beadhouse.entity.query.AiCareNoteDraftQuery;
import com.shanzhu.beadhouse.entity.query.FamilyReportQuery;
import com.shanzhu.beadhouse.service.common.AiAuditRecorder;
import com.shanzhu.beadhouse.service.common.AiDataScopeService;
import com.shanzhu.beadhouse.service.common.AiMetricRecorder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fixed synthetic corpus exercises public production methods and a real loopback HTTP provider. */
class AiCareQualityEvaluationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void evaluateFixedSyntheticCorpus() throws Exception {
        byte[] fixtureBytes;
        try (InputStream input = getClass().getResourceAsStream("/ai-quality/care-cases.json")) {
            fixtureBytes = readBytes(input);
        }
        JsonNode cases = json.readTree(fixtureBytes);
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (JsonNode item : cases) {
            boolean passed = false;
            try {
                evaluate(item);
                passed = true;
            } catch (AssertionError | Exception failure) {
                // Do not serialize exception messages: a provider/source payload can appear in them.
                failures.add(item.path("id").asText() + ":" + failure.getClass().getSimpleName());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", item.path("id").asText());
            result.put("category", item.path("category").asText());
            result.put("passed", passed);
            results.add(result);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("mode", "offline-contract");
        report.put("fixtureSha256", hex(MessageDigest.getInstance("SHA-256").digest(fixtureBytes)));
        report.put("caseCount", results.size());
        report.put("passed", results.size() - failures.size());
        report.put("failed", failures.size());
        report.put("cases", results);
        report.put("limitations", Arrays.asList("Synthetic records and controlled loopback HTTP responses only.",
                "No real LLM quality, latency, or clinical correctness evaluation.",
                "Exact-source checks do not prove category semantics or resolve multi-person attribution.",
                "Conservative category hints may reject synonyms and use the editable local fallback.",
                "Extraction preserves source wording; human review remains required."));
        Path output = Paths.get("target", "ai-quality", "report.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertThat(failures).as("Synthetic case IDs (see target/ai-quality/report.json)").isEmpty();
    }

    private void evaluate(JsonNode item) throws Exception {
        AiCareServiceImpl service = new AiCareServiceImpl();
        CareNoteMapper mapper = mock(CareNoteMapper.class);
        AiDataScopeService scope = mock(AiDataScopeService.class);
        AiMetricRecorder metrics = mock(AiMetricRecorder.class);
        List<CareNote> notes = new ArrayList<>();
        for (JsonNode source : item.path("notes")) notes.add(json.treeToValue(source, CareNote.class));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(notes);
        when(scope.isAdmin()).thenReturn(true);
        ElderMapper elders = mock(ElderMapper.class);
        StaffMapper staff = mock(StaffMapper.class);
        when(elders.selectBatchIds(any())).thenReturn(Collections.emptyList());
        when(staff.selectBatchIds(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(service, "careNoteMapper", mapper);
        ReflectionTestUtils.setField(service, "elderMapper", elders);
        ReflectionTestUtils.setField(service, "staffMapper", staff);
        ReflectionTestUtils.setField(service, "dataScopeService", scope);
        ReflectionTestUtils.setField(service, "auditRecorder", mock(AiAuditRecorder.class));
        ReflectionTestUtils.setField(service, "metricRecorder", metrics);
        ReflectionTestUtils.setField(service, "objectMapper", json);
        boolean enabled = item.path("enabled").asBoolean(true);
        ReflectionTestUtils.setField(service, "providerEnabled", enabled);
        ReflectionTestUtils.setField(service, "providerApiKey", "synthetic-test-token");
        ReflectionTestUtils.setField(service, "providerModel", "synthetic-provider");
        ReflectionTestUtils.setField(service, "providerTimeoutMs", 80);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/chat", exchange -> {
            requests.incrementAndGet();
            try {
                request.set(json.readTree(readBytes(exchange.getRequestBody())));
                if (item.path("delayMs").asInt() > 0) Thread.sleep(item.path("delayMs").asInt());
                JsonNode content = item.path("response");
                String wire;
                if (item.has("wire")) wire = item.path("wire").asText();
                else {
                    ObjectNode envelope = json.createObjectNode();
                    envelope.putArray("choices").addObject().putObject("message")
                            .put("content", content.isTextual() ? content.asText() : json.writeValueAsString(content));
                    wire = json.writeValueAsString(envelope);
                }
                byte[] body = wire.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(item.path("status").asInt(200), body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) {
                // A timeout deliberately disconnects the HTTP client.
            } finally {
                exchange.close();
            }
        });
        server.start();
        ReflectionTestUtils.setField(service, "providerUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
        try {
            if (item.path("denyAccess").asBoolean()) {
                doThrow(new SecurityException("synthetic-denial")).when(scope).assertElderAccess(17L);
                boolean denied = false;
                try { invoke(service, item); } catch (SecurityException expected) { denied = true; }
                assertThat(denied).isTrue();
                assertThat(requests.get()).isZero();
                verify(mapper, never()).selectList(any(Wrapper.class));
                return;
            }
            Result result = invoke(service, item);
            assertThat(result.getCode()).isEqualTo(item.path("expectedCode").asInt(200));
            if (result.getCode() != 200) {
                assertThat(requests.get()).isZero();
                return;
            }
            JsonNode actual = json.valueToTree(result.getData());
            JsonNode expected = item.path("expected");
            assertThat(actual.path("modelUsed").asBoolean()).isEqualTo(expected.path("modelUsed").asBoolean());
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = expected.path("fields").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                assertThat(actual.get(field.getKey())).as(field.getKey()).isEqualTo(field.getValue());
            }
            for (JsonNode value : expected.path("contains")) {
                assertThat(actual.path("summary").asText()).contains(value.asText());
            }
            for (JsonNode value : expected.path("excludes")) {
                assertThat(actual.path("summary").asText()).doesNotContain(value.asText());
            }
            if (item.has("expectedRequests")) assertThat(requests.get()).isEqualTo(item.path("expectedRequests").asInt());
            if (item.path("jsonContext").asBoolean()) {
                JsonNode context = json.readTree(request.get().path("messages").path(1).path("content").asText());
                assertThat(context.isObject()).isTrue();
                assertThat(context.path("records").size()).isEqualTo(notes.size());
                assertThat(context.path("records").path(0).path("observation").asText()).isEqualTo(notes.get(0).getObservation());
            }
            boolean model = actual.path("modelUsed").asBoolean();
            String operation = item.path("operation").asText();
            AiMetricRecorder.Feature feature = "draft".equals(operation) ? AiMetricRecorder.Feature.CARE_DRAFT
                    : "family".equals(operation) ? AiMetricRecorder.Feature.CARE_FAMILY : AiMetricRecorder.Feature.CARE_HANDOVER;
            AiMetricRecorder.Outcome outcome = "family".equals(operation) && notes.isEmpty()
                    ? AiMetricRecorder.Outcome.NO_HIT : enabled && !model ? AiMetricRecorder.Outcome.FALLBACK : AiMetricRecorder.Outcome.SUCCESS;
            verify(metrics).record(eq(feature), eq(AiMetricRecorder.Stage.PIPELINE), eq(outcome),
                    eq(model ? "MODEL" : "LOCAL"), eq(model), isNull(), anyLong(), anyInt(), anyInt(), any(), isNull());
            verify(mapper, never()).insert(any(CareNote.class));
            verify(mapper, never()).updateById(any(CareNote.class));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private Result invoke(AiCareServiceImpl service, JsonNode item) {
        String operation = item.path("operation").asText();
        if ("draft".equals(operation)) {
            AiCareNoteDraftQuery query = new AiCareNoteDraftQuery();
            query.setElderId(17L);
            query.setSourceText(item.path("source").asText());
            return service.draft(query);
        }
        if ("handover".equals(operation)) return service.handover(new Date(0));
        FamilyReportQuery query = new FamilyReportQuery();
        query.setElderId(17L); query.setStartDate(new Date(0)); query.setEndDate(new Date(86400000L));
        return service.familyReport(query);
    }

    private static byte[] readBytes(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }
}

