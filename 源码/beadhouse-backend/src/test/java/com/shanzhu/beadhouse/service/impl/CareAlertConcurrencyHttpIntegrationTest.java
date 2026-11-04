package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A real MySQL row-lock barrier makes the cross-plan scan/registration race reproducible. */
@EnabledIfEnvironmentVariable(named = "ALERT_HTTP_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}",
        "spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
        "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}", "spring.redis.password=scope-redis-only",
        "ai.rag.enabled=false", "ai.provider.enabled=false", "security.password-reset.email-enabled=false",
        "spring.quartz.auto-startup=false"})
class CareAlertConcurrencyHttpIntegrationTest {
    private static final long ROLE = 923000L;
    private static final long STAFF = 923001L;
    private static final long ELDER = 923101L;
    private static final String PHONE = "19900003201";
    private static final String PASSWORD = "care-alert-lock-fixture-only";

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private boolean fixtureCreated;
    private String token;
    private String yesterday;
    private long firstPlan;
    private long secondPlan;
    private long firstAlert;
    private String secondKey;

    @BeforeEach void createOnlyThisTestsStaffElderAndPlans() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(45000);
        http.getRestTemplate().setRequestFactory(factory);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role WHERE id=?", Long.class, ROLE)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE id=? OR phone=?", Long.class, STAFF, PHONE)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder WHERE id=?", Long.class, ELDER)).isZero();
        fixtureCreated = true;
        jdbc.update("INSERT INTO role(id,name,create_id,create_time,update_id,update_time) "
                + "VALUES(?,'alert-lock',1,NOW(),1,NOW())", ROLE);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) "
                + "SELECT ?,id,1,NOW(),1,NOW() FROM auth WHERE url IN ('/ai/daily/index','/ai/medication/index')", ROLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_auth WHERE role_id=?", Long.class, ROLE)).isEqualTo(2);
        jdbc.update("INSERT INTO staff(id,role_id,name,id_num,age,sex,phone,email,pass,avator,address,leave_flag,"
                        + "create_id,create_time,update_id,update_time) "
                        + "VALUES(?,?,'alert-race','990101199001923001',30,'女',?,'alert-race@example.invalid',?,'','lock fixture','N',?,NOW(),?,NOW())",
                STAFF, ROLE, PHONE, new BCryptPasswordEncoder().encode(PASSWORD), STAFF, STAFF);
        jdbc.update("INSERT INTO elder(id,name,id_num,age,sex,phone,address,balance,check_flag,"
                        + "create_id,create_time,update_id,update_time) "
                        + "VALUES(?,'ac-elder','990101195001923101',70,'女',?,'lock fixture',100,'入住',?,NOW(),?,NOW())",
                ELDER, PHONE, STAFF, STAFF);
        jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active,create_id,update_id) VALUES(?,?,'Y',?,?)",
                ELDER, STAFF, STAFF, STAFF);
        yesterday = LocalDate.now().minusDays(1).toString();
        // Put both plans above every existing execution key, including orphaned
        // historical rows, so the old range read reaches the index's final gap.
        long lastPlan = Math.max(jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM medication_plan", Long.class),
                jdbc.queryForObject("SELECT COALESCE(MAX(plan_id),0) FROM medication_execution", Long.class));
        firstPlan = Math.addExact(lastPlan, 1L);
        secondPlan = Math.addExact(lastPlan, 2L);
        insertPlan(firstPlan, "barrier-plan");
        insertPlan(secondPlan, "register-plan");
        String firstKey = yesterday + "-med-" + firstPlan + "-早";
        secondKey = yesterday + "-med-" + secondPlan + "-早";
        jdbc.update("INSERT INTO ai_care_alert(alert_key,elder_id,source_id,kind,detail,evidence_key,task_key) "
                        + "VALUES(?,?,?,'MEDICATION_UNRECORDED','fixture awaiting review',REPEAT('a',64),?)",
                firstKey, ELDER, firstPlan, firstKey);
        firstAlert = jdbc.queryForObject("SELECT id FROM ai_care_alert WHERE alert_key=?", Long.class, firstKey);
        JsonNode login = request(HttpMethod.POST, "/account/login", null, map("phone", PHONE, "pass", PASSWORD));
        assertSuccess(login, "fixture login");
        token = login.path("data").path("token").asText();
        assertThat(token).isNotBlank();
    }

    @AfterEach void removeOnlyOwnedFixtures() {
        if (!fixtureCreated) return;
        try {
            if (token != null) request(HttpMethod.DELETE, "/account/logout", token, null);
        } finally {
            jdbc.update("DELETE FROM ai_daily_task WHERE elder_id=?", ELDER);
            jdbc.update("DELETE FROM ai_care_alert WHERE elder_id=?", ELDER);
            jdbc.update("DELETE FROM medication_execution WHERE elder_id=?", ELDER);
            jdbc.update("DELETE FROM medication_plan WHERE elder_id=?", ELDER);
            jdbc.update("DELETE FROM elder_staff_assignment WHERE elder_id=? AND staff_id=?", ELDER, STAFF);
            jdbc.update("DELETE FROM elder WHERE id=? AND create_id=?", ELDER, STAFF);
            jdbc.update("DELETE FROM ai_audit_log WHERE operator_id=? OR create_id=?", STAFF, STAFF);
            jdbc.update("DELETE FROM role_auth WHERE role_id=?", ROLE);
            jdbc.update("DELETE FROM staff WHERE id=? AND role_id=?", STAFF, ROLE);
            jdbc.update("DELETE FROM role WHERE id=?", ROLE);
        }
    }

    @Test void scanningOnePlanCannotDeadlockRegistrationForTheNextPlan() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<JsonNode> scan = null;
        Future<JsonNode> execution = null;
        try {
            try (Connection barrier = dataSource.getConnection()) {
                barrier.setAutoCommit(false);
                try {
                    long barrierConnection;
                    try (Statement statement = barrier.createStatement();
                         ResultSet row = statement.executeQuery("SELECT CONNECTION_ID()")) {
                        assertThat(row.next()).isTrue();
                        barrierConnection = row.getLong(1);
                    }
                    try (PreparedStatement lock = barrier.prepareStatement("SELECT id FROM ai_care_alert WHERE id=? FOR UPDATE")) {
                        lock.setLong(1, firstAlert);
                        try (ResultSet row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
                    }
                    scan = workers.submit(() -> request(HttpMethod.POST, "/ai/daily/alerts/scan?date=" + yesterday, token, null));
                    long scanTransaction = awaitScanAtAlertBarrier(scan, barrierConnection);
                    execution = workers.submit(() -> request(HttpMethod.POST, "/ai/medication/execute", token,
                            map("planId", secondPlan, "executionDate", yesterday, "period", "早", "status", "DONE")));
                    // With the fix this request may finish immediately. On the old
                    // path it has plan B locked and waits on scan A's execution gap.
                    awaitExecutionCompletedOrWaitingOnScan(execution, scanTransaction);
                } finally {
                    barrier.rollback();
                }
            }
            JsonNode scanned = scan.get(30, TimeUnit.SECONDS);
            JsonNode executed = execution.get(30, TimeUnit.SECONDS);
            assertSuccess(scanned, "scan while the next plan is being registered");
            assertSuccess(executed, "registration while the earlier plan is being scanned");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM medication_execution WHERE plan_id=?", Long.class, secondPlan)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM medication_execution WHERE plan_id=? AND execution_date=? AND period='早'",
                    String.class, secondPlan, yesterday)).isEqualTo("DONE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_care_alert WHERE alert_key=? AND state!='RESOLVED'",
                    Long.class, secondKey)).isZero();
        } catch (Exception | AssertionError failure) {
            saveLockEvidence(failure);
            throw failure;
        } finally {
            workers.shutdown();
            if (!workers.awaitTermination(45, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).as("HTTP workers finish before fixture cleanup").isTrue();
            }
        }
    }

    private long awaitScanAtAlertBarrier(Future<JsonNode> scan, long barrierConnection) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            List<Long> waiting = jdbc.queryForList(
                    "SELECT DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID FROM performance_schema.data_lock_waits w "
                            + "JOIN performance_schema.data_locks b ON b.ENGINE=w.ENGINE AND b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                            + "JOIN performance_schema.threads t ON t.THREAD_ID=w.BLOCKING_THREAD_ID "
                            + "WHERE t.PROCESSLIST_ID=? AND b.OBJECT_SCHEMA=DATABASE() AND b.OBJECT_NAME='ai_care_alert' "
                            + "AND b.INDEX_NAME='PRIMARY' AND b.LOCK_DATA=?", Long.class, barrierConnection, String.valueOf(firstAlert));
            if (!waiting.isEmpty()) {
                assertThat(waiting).as("exactly the scan reaches the owned alert-row barrier").hasSize(1);
                return waiting.get(0);
            }
            if (scan.isDone()) {
                assertSuccess(scan.get(), "scan before reaching the alert-row barrier");
                throw new AssertionError("scan completed without reaching the owned alert-row barrier");
            }
            Thread.sleep(25);
        }
        throw new AssertionError("scan did not reach the owned ai_care_alert row lock within 15 seconds");
    }

    private void awaitExecutionCompletedOrWaitingOnScan(Future<JsonNode> execution, long scanTransaction) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (execution.isDone()) return;
            long waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                            + "JOIN performance_schema.data_locks r ON r.ENGINE=w.ENGINE AND r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                            + "WHERE w.BLOCKING_ENGINE_TRANSACTION_ID=? AND r.OBJECT_SCHEMA=DATABASE() "
                            + "AND r.OBJECT_NAME='medication_execution' AND r.LOCK_MODE LIKE '%INSERT_INTENTION%'",
                    Long.class, scanTransaction);
            if (waiting > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("registration neither completed nor reached the scan's medication_execution lock within 15 seconds");
    }

    private void saveLockEvidence(Throwable failure) {
        try {
            Map<String, Object> status = jdbc.queryForMap("SHOW ENGINE INNODB STATUS");
            StringBuilder evidence = new StringBuilder("Care alert concurrency failure: ")
                    .append(failure.getClass().getSimpleName()).append('\n');
            for (Map.Entry<String, Object> field : status.entrySet()) {
                if ("Status".equalsIgnoreCase(field.getKey())) evidence.append(field.getValue());
            }
            Path path = Paths.get("target", "care-alert-concurrency-innodb.txt");
            Files.createDirectories(path.getParent());
            Files.write(path, evidence.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception diagnosticFailure) {
            failure.addSuppressed(new IllegalStateException("Could not save InnoDB lock evidence", diagnosticFailure));
        }
    }

    private void insertPlan(long id, String name) {
        jdbc.update("INSERT INTO medication_plan(id,elder_id,medicine_name,dose_instruction,periods,start_date,enabled,create_id,update_id) "
                        + "VALUES(?,?,?,'fixture instruction','早',?,'Y',?,?)",
                id, ELDER, name, yesterday, STAFF, STAFF);
    }

    private void assertSuccess(JsonNode response, String operation) {
        assertThat(response).as(operation + " response").isNotNull();
        assertThat(response.path("code").asInt()).as("%s: code=%s, msg=%s", operation,
                response.path("code").asText(), response.path("msg").asText()).isEqualTo(200);
    }

    private JsonNode request(HttpMethod method, String path, String loginToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (loginToken != null) headers.set("token", loginToken);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
}
