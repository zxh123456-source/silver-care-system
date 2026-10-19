package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP, database locks and fault injection; only dedicated disposable services are allowed. */
@EnabledIfEnvironmentVariable(named = "SCOPE_HTTP_REDIS_PORT", matches = "[0-9]+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}", "spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
        "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}", "spring.redis.password=scope-redis-only",
        "ai.rag.enabled=false", "ai.provider.enabled=false", "security.password-reset.email-enabled=false",
        "spring.quartz.auto-startup=false"})
class RechargeHttpIntegrationTest {
    private static final long ROLE = 862000, OWN = 862001, OTHER = 862002, ADMIN = 862003;
    private static final long FIRST = 862101, SECOND = 862102, FOREIGN = 862103;
    private static final String PASSWORD = "recharge-fixture-only";
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private String own, other, admin;
    private boolean created;

    @BeforeEach void fixture() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        factory.setReadTimeout(30000);
        http.getRestTemplate().setRequestFactory(factory);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE id IN (?,?,?)", Long.class, OWN, OTHER, ADMIN)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder WHERE id IN (?,?,?)", Long.class, FIRST, SECOND, FOREIGN)).isZero();
        created = true;
        jdbc.update("INSERT INTO role(id,name,create_id,create_time,update_id,update_time) VALUES(?,'rc-role',1,NOW(),1,NOW())", ROLE);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) SELECT ?,id,1,NOW(),1,NOW() FROM auth", ROLE);
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        for (long staff : Arrays.asList(OWN, OTHER, ADMIN))
            jdbc.update("INSERT INTO staff(id,role_id,name,id_num,age,sex,phone,email,pass,avator,address,leave_flag,"
                            + "create_id,create_time,update_id,update_time) VALUES(?,?,'rc-staff',?,30,'男',?,'rc@example.invalid',"
                            + "?,'fixture-avatar','fixture','N',1,NOW(),1,NOW())",
                    staff, staff == ADMIN ? 1L : ROLE, "990101199001" + staff, "19900" + staff, hash);
        for (long elder : Arrays.asList(FIRST, SECOND, FOREIGN))
            jdbc.update("INSERT INTO elder(id,name,id_num,age,sex,phone,address,balance,check_flag,create_id,create_time,update_id,update_time) "
                            + "VALUES(?,'rc-elder',?,70,'女','19900002001','fixture',?,'入住',?,NOW(),?,NOW())",
                    elder, "990101195001" + elder, elder == FIRST ? 100 : elder == SECOND ? 200 : 300, OTHER, OTHER);
        assign(FIRST, OWN); assign(FIRST, OTHER); assign(SECOND, OWN);
        own = login(OWN); other = login(OTHER); admin = login(ADMIN);
    }

    @AfterEach void cleanup() {
        if (!created) return;
        for (String token : Arrays.asList(own, other, admin))
            if (token != null) call(HttpMethod.DELETE, "/account/logout", token, null);
        jdbc.execute("DROP TRIGGER IF EXISTS recharge_test_reject_insert");
        // Allows the same behavioral regression to run against the pre-migration version for red/green evidence.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name='deposit_recharge_ledger'", Long.class) == 1)
            jdbc.update("DELETE FROM deposit_recharge_ledger WHERE elder_id IN (?,?,?)", FIRST, SECOND, FOREIGN);
        jdbc.update("DELETE FROM elder_staff_assignment WHERE elder_id IN (?,?,?)", FIRST, SECOND, FOREIGN);
        jdbc.update("DELETE FROM elder WHERE id IN (?,?,?)", FIRST, SECOND, FOREIGN);
        jdbc.update("DELETE FROM ai_audit_log WHERE create_id IN (?,?,?)", OWN, OTHER, ADMIN);
        jdbc.update("DELETE FROM role_auth WHERE role_id=?", ROLE);
        jdbc.update("DELETE FROM staff WHERE id IN (?,?,?)", OWN, OTHER, ADMIN);
        jdbc.update("DELETE FROM role WHERE id=?", ROLE);
    }

    @Test void canonicalAmountsAndKeysReplayTheImmutableOriginalReceipt() {
        String key = key();
        JsonNode receipt = ok(recharge(own, FIRST, "10", key.toUpperCase(Locale.ROOT)));
        assertThat(receipt.path("requestId").asText()).isEqualTo(key);
        assertThat(receipt.path("id").asLong()).isPositive();
        assertThat(receipt.path("elderId").asLong()).isEqualTo(FIRST);
        assertThat(receipt.path("staffId").asLong()).isEqualTo(OWN);
        assertThat(receipt.path("createdAt").asText()).isNotBlank();
        assertThat(receipt.path("balanceBefore").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(receipt.path("balanceAfter").decimalValue()).isEqualByComparingTo("110.00");
        ok(recharge(own, FIRST, "5", key()));
        assertThat(ok(recharge(own, FIRST, "10.00", key))).isEqualTo(receipt);
        assertThat(ok(call(HttpMethod.GET, receiptPath(FIRST, key), own, null))).isEqualTo(receipt);
        balance(FIRST, "115.00");
        assertThat(ledgerCount()).isEqualTo(2);
    }

    @Test void reusingAKeyWithAnotherAmountElderOrActorIsAnAtomicConflict() {
        String key = key();
        JsonNode receipt = ok(recharge(own, FIRST, "10.00", key));
        String before = fingerprint();
        code(recharge(own, FIRST, "11.00", key), 409);
        code(recharge(own, SECOND, "10.00", key), 409);
        code(recharge(other, FIRST, "10.00", key), 409);
        code(recharge(admin, FIRST, "10.00", key), 409);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(ok(recharge(own, FIRST, "10.0", key))).isEqualTo(receipt);
    }

    @Test void invalidKeysAndAmountsNeverCreateAnEntry() {
        String before = fingerprint();
        for (String key : Arrays.asList(null, "", "not-a-uuid", "1-1-1-1-1", " " + key()))
            code(recharge(own, FIRST, "10.00", key), 400);
        for (String amount : Arrays.asList(null, "0", "-1", "0.001", "100000000"))
            code(recharge(own, FIRST, amount, key()), 400);
        assertThat(fingerprint()).isEqualTo(before);
        JsonNode receipt = ok(recharge(own, FIRST, "0.01", key()));
        assertThat(receipt.path("amount").decimalValue()).isEqualByComparingTo("0.01");
        balance(FIRST, "100.01");
    }

    @Test void ledgerInsertFailureRollsBackTheCreditAndTheSameKeyCanRecover() {
        String key = key(), before = fingerprint();
        jdbc.execute("CREATE TRIGGER recharge_test_reject_insert BEFORE INSERT ON deposit_recharge_ledger "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic recharge ledger failure'");
        try {
            assertThat(recharge(own, FIRST, "10.00", key).getBody().path("code").asInt()).isNotEqualTo(200);
            assertThat(fingerprint()).isEqualTo(before);
        } finally { jdbc.execute("DROP TRIGGER recharge_test_reject_insert"); }
        JsonNode receipt = ok(recharge(own, FIRST, "10.00", key));
        assertThat(ok(recharge(own, FIRST, "10.00", key))).isEqualTo(receipt);
        balance(FIRST, "110.00");
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test void completedReceiptSurvivesCheckoutButNewCreditsDoNot() {
        String key = key();
        JsonNode receipt = ok(recharge(own, FIRST, "10.00", key));
        jdbc.update("UPDATE elder SET check_flag='已退住' WHERE id=?", FIRST);
        assertThat(ok(recharge(own, FIRST, "10.00", key))).isEqualTo(receipt);
        assertThat(ok(call(HttpMethod.GET, receiptPath(FIRST, key), own, null))).isEqualTo(receipt);
        String before = fingerprint();
        assertThat(recharge(own, FIRST, "10.00", key()).getBody().path("code").asInt()).isNotEqualTo(200);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void currentScopeIsCheckedForReplaysReceiptReadsAndLedgerPages() {
        String key = key();
        JsonNode receipt = ok(recharge(own, FIRST, "10.00", key));
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE elder_id=? AND staff_id=?", FIRST, OWN);
        String before = fingerprint();
        denied(recharge(own, FIRST, "10.00", key));
        denied(call(HttpMethod.GET, receiptPath(FIRST, key), own, null));
        denied(call(HttpMethod.GET, pagePath(FIRST, 1, 10), own, null));
        denied(recharge(own, FOREIGN, "10.00", key()));
        denied(call(HttpMethod.GET, pagePath(FOREIGN, 1, 10), own, null));
        assertThat(fingerprint()).isEqualTo(before);
        jdbc.update("UPDATE elder_staff_assignment SET active='Y' WHERE elder_id=? AND staff_id=?", FIRST, OWN);
        assertThat(ok(recharge(own, FIRST, "10.00", key))).isEqualTo(receipt);
        balance(FIRST, "110.00");
    }

    @Test void receiptReadsBindBothTheStoredElderAndTheActorWithAnAdminAuditException() {
        String key = key();
        JsonNode receipt = ok(recharge(own, FIRST, "10.00", key));
        denied(call(HttpMethod.GET, receiptPath(SECOND, key), own, null));
        denied(call(HttpMethod.GET, receiptPath(SECOND, key), admin, null));
        denied(call(HttpMethod.GET, receiptPath(FIRST, key), other, null));
        assertThat(ok(call(HttpMethod.GET, receiptPath(FIRST, key), admin, null))).isEqualTo(receipt);
        code(call(HttpMethod.GET, receiptPath(FIRST, key()), own, null), 404);
        JsonNode audit = ok(call(HttpMethod.GET, pagePath(FIRST, 1, 10), other, null));
        assertThat(audit.path("total").asLong()).isEqualTo(1);
        assertThat(audit.path("list").get(0)).isEqualTo(receipt);
        balance(FIRST, "110.00");
    }

    @Test void ledgerPaginationIsBoundedAndScopedInSql() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) ids.add(ok(recharge(own, FIRST, "1.00", key())).path("id").asLong());
        ok(recharge(own, SECOND, "2.00", key()));
        JsonNode page = ok(call(HttpMethod.GET, pagePath(FIRST, 1, 2), own, null));
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(page.path("list").size()).isEqualTo(2);
        assertThat(page.path("list").get(0).path("id").asLong()).isEqualTo(ids.get(2));
        assertThat(page.path("list").get(1).path("id").asLong()).isEqualTo(ids.get(1));
        JsonNode next = ok(call(HttpMethod.GET, pagePath(FIRST, 2, 2), own, null));
        assertThat(next.path("total").asLong()).isEqualTo(3);
        assertThat(next.path("list").size()).isEqualTo(1);
        assertThat(next.path("list").get(0).path("id").asLong()).isEqualTo(ids.get(0));
        assertThat(ok(call(HttpMethod.GET, pagePath(FIRST, 1, 101), own, null)).path("pageSize").asInt()).isEqualTo(100);
        code(call(HttpMethod.GET, pagePath(FIRST, 0, 10), own, null), 400);
        code(call(HttpMethod.GET, pagePath(FIRST, 1, 0), own, null), 400);
        assertThat(ok(call(HttpMethod.GET, pagePath(FIRST, Integer.MAX_VALUE, 100), own, null)).path("list").size()).isZero();
    }

    @Test void differentEldersRacingForOneKeyCannotLeaveAnUnreceiptedCredit() throws Exception {
        String key = key();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        try {
            try (Connection blocker = dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                try (PreparedStatement statement = blocker.prepareStatement("SELECT id FROM elder WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
                    statement.setLong(1, FIRST); statement.setLong(2, SECOND);
                    try (ResultSet rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.next()).isTrue(); }
                    futures.add(workers.submit(() -> recharge(own, FIRST, "10.00", key)));
                    futures.add(workers.submit(() -> recharge(own, SECOND, "10.00", key)));
                    long waiting = 0, deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (System.nanoTime() < deadline) {
                        waiting = jdbc.queryForObject("SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w "
                                + "JOIN performance_schema.data_locks b ON b.ENGINE=w.ENGINE AND b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                                + "WHERE b.OBJECT_SCHEMA=DATABASE() AND b.OBJECT_NAME='elder' AND b.INDEX_NAME='PRIMARY' AND b.LOCK_DATA IN (?,?)",
                                Long.class, String.valueOf(FIRST), String.valueOf(SECOND));
                        if (waiting == 2) break;
                        Thread.sleep(50);
                    }
                    assertThat(waiting).as("both requests reach their separate elder lock barriers").isEqualTo(2);
                } finally { blocker.rollback(); }
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : futures) codes.add(future.get(30, TimeUnit.SECONDS).getBody().path("code").asInt());
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT SUM(balance) FROM elder WHERE id IN (?,?)", BigDecimal.class, FIRST, SECOND))
                    .isEqualByComparingTo("310.00");
            assertThat(ledgerCount()).isEqualTo(1);
            long winner = jdbc.queryForObject("SELECT elder_id FROM deposit_recharge_ledger WHERE request_id=?", Long.class, key);
            balance(winner, winner == FIRST ? "110.00" : "210.00");
            balance(winner == FIRST ? SECOND : FIRST, winner == FIRST ? "200.00" : "100.00");
            ok(recharge(own, winner, "10.00", key));
            assertThat(ledgerCount()).isEqualTo(1);
        } finally { workers.shutdownNow(); workers.awaitTermination(30, TimeUnit.SECONDS); }
    }

    @Test void databaseRejectsImpossibleImmutableReceipts() {
        String sql = "INSERT INTO deposit_recharge_ledger(request_id,elder_id,staff_id,amount,balance_before,balance_after) VALUES(?,?,?,?,?,?)";
        checkViolation(() -> jdbc.update(sql, key(), FIRST, OWN, -1, 100, 99), "chk_recharge_positive_amount");
        checkViolation(() -> jdbc.update(sql, key(), FIRST, OWN, 10, 100, 999), "chk_recharge_balance_delta");
        assertThat(ledgerCount()).isZero();
        balance(FIRST, "100.00");
    }

    private void checkViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String constraint) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class, failure -> {
            assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class, sql -> {
                assertThat(sql.getErrorCode()).isEqualTo(3819);
                assertThat(sql.getMessage()).contains(constraint);
            });
        });
    }

    private void assign(long elder, long staff) {
        jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active,create_id,update_id) VALUES(?,?,'Y',?,?)", elder, staff, ADMIN, ADMIN);
    }
    private String login(long staff) {
        return ok(call(HttpMethod.POST, "/account/login", null, map("phone", "19900" + staff, "pass", PASSWORD))).path("token").asText();
    }
    private String key() { return UUID.randomUUID().toString(); }
    private String receiptPath(long elder, String key) { return "/depositRecharge/getRechargeReceipt?elderId=" + elder + "&requestId=" + key; }
    private String pagePath(long elder, int page, int size) { return "/depositRecharge/pageRechargeLedger?elderId=" + elder + "&pageNum=" + page + "&pageSize=" + size; }
    private ResponseEntity<JsonNode> recharge(String token, long elder, String amount, String key) {
        return call(HttpMethod.PUT, "/depositRecharge/recharge", token, map("elderId", elder, "amount", amount, "requestId", key));
    }
    private JsonNode ok(ResponseEntity<JsonNode> response) { return code(response, 200).path("data"); }
    private void denied(ResponseEntity<JsonNode> response) { assertThat(response.getStatusCodeValue()).isEqualTo(403); code(response, 403); }
    private JsonNode code(ResponseEntity<JsonNode> response, int expected) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asInt()).as(response.getBody().toString()).isEqualTo(expected);
        return response.getBody();
    }
    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set("token", token);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    private void balance(long elder, String expected) {
        assertThat(jdbc.queryForObject("SELECT balance FROM elder WHERE id=?", BigDecimal.class, elder)).isEqualByComparingTo(expected);
    }
    private long ledgerCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM deposit_recharge_ledger WHERE elder_id IN (?,?,?)", Long.class, FIRST, SECOND, FOREIGN);
    }
    private String fingerprint() {
        return jdbc.queryForList("SELECT * FROM elder WHERE id IN (?,?,?) ORDER BY id", FIRST, SECOND, FOREIGN).toString()
                + jdbc.queryForList("SELECT * FROM deposit_recharge_ledger WHERE elder_id IN (?,?,?) ORDER BY id", FIRST, SECOND, FOREIGN);
    }
}
