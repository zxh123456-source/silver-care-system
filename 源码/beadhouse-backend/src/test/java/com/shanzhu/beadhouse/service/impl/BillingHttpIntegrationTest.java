package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.entity.po.Elder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SCOPE_HTTP_REDIS_PORT", matches="[0-9]+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}", "spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
    "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}", "spring.redis.password=scope-redis-only",
    "ai.rag.enabled=false", "ai.provider.enabled=false", "security.password-reset.email-enabled=false",
    "spring.quartz.auto-startup=false"})
class BillingHttpIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ElderMapper elders;
    private String token;

    @BeforeEach void fixture() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false); factory.setReadTimeout(30000);
        http.getRestTemplate().setRequestFactory(factory);
        String password = "billing-test-only";
        jdbc.update("UPDATE staff SET pass=? WHERE id=1", new BCryptPasswordEncoder().encode(password));
        jdbc.update("UPDATE elder SET balance=100,check_flag='入住' WHERE id=4");
        JsonNode login = http.postForObject("/account/login", map("phone", jdbc.queryForObject("SELECT phone FROM staff WHERE id=1", String.class), "pass", password), JsonNode.class);
        assertThat(login.path("code").asInt()).isEqualTo(200);
        token = login.path("data").path("token").asText();
    }

    @Test void invalidCreditsNeverChangeTheBalance() {
        for (String value : Arrays.asList("-1", "0", "0.001", "100000000", null)) {
            JsonNode result = call("/depositRecharge/recharge", newRecharge(value));
            assertThat(result.path("code").asInt()).as("amount=%s", value).isEqualTo(400);
            assertBalance("100.00");
        }
    }

    @Test void concurrentCreditsAllReachTheBalance() throws Exception {
        List<Callable<JsonNode>> credits = new ArrayList<>();
        // Six independent business intents keep six distinct request IDs.
        for (int index = 0; index < 6; index++) credits.add(() -> call("/depositRecharge/recharge", newRecharge("10.00")));
        List<JsonNode> results = contend("elder", 4L, credits);
        assertThat(results).allSatisfy(result -> assertThat(result.path("code").asInt()).isEqualTo(200));
        assertBalance("160.00");
    }

    @Test void replayingTheSameRechargeReturnsItsReceiptWithoutAnotherCredit() {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> request = map("elderId", 4, "amount", "10.00", "requestId", requestId);
        JsonNode first = call("/depositRecharge/recharge", request);
        JsonNode replay = call("/depositRecharge/recharge", request);
        assertThat(first.path("code").asInt()).isEqualTo(200);
        assertThat(replay.path("code").asInt()).isEqualTo(200);
        assertBalance("110.00");
        assertThat(first.path("data").path("requestId").asText()).isEqualTo(requestId);
        assertThat(replay.path("data")).isEqualTo(first.path("data"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deposit_recharge_ledger WHERE request_id=?", Long.class, requestId)).isEqualTo(1);
    }

    @Test void concurrentRetriesOfOneRechargeCreditOnlyOnce() throws Exception {
        String requestId = UUID.randomUUID().toString();
        List<Callable<JsonNode>> calls = new ArrayList<>();
        for (int index = 0; index < 6; index++) calls.add(() -> call("/depositRecharge/recharge",
                map("elderId", 4, "amount", "10.00", "requestId", requestId)));
        List<JsonNode> results = contend("elder", 4L, calls);
        assertThat(results).allSatisfy(result -> assertThat(result.path("code").asInt()).isEqualTo(200));
        assertBalance("110.00");
        assertThat(results).allSatisfy(result -> assertThat(result.path("data")).isEqualTo(results.get(0).path("data")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deposit_recharge_ledger WHERE request_id=?", Long.class, requestId)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"nurse_reserve", "order"})
    void simultaneousExecutionChargesEachSourceOnlyOnce(String table) throws Exception {
        long id = source(table, "10.00"), before = consumptionCount();
        List<Callable<JsonNode>> executions = new ArrayList<>();
        for (int index = 0; index < 4; index++) executions.add(() -> execute(table, id));
        List<JsonNode> results = contend(table, id, executions);
        assertThat(results.stream().filter(result -> result.path("code").asInt() == 200).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result.path("code").asInt() != 200)).allSatisfy(
                result -> assertThat(result.path("msg").asText()).isEqualTo("该订单已完成"));
        assertBalance("90.00");
        assertThat(consumptionCount()).isEqualTo(before + 1);
        assertThat(state(table, id)).isEqualTo("Y");
    }

    @Test void differentSourcesCannotOverdrawTheSameBalance() throws Exception {
        long nursing = source("nurse_reserve", "60.00"), meal = source("order", "60.00"), before = consumptionCount();
        List<JsonNode> results = contend("elder", 4L, Arrays.asList(() -> execute("nurse_reserve", nursing), () -> execute("order", meal)));
        assertThat(results.stream().filter(result -> result.path("code").asInt() == 200).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result.path("code").asInt() != 200)).allSatisfy(
                result -> assertThat(result.path("msg").asText()).isEqualTo("老人余额不足"));
        assertBalance("40.00");
        assertThat(consumptionCount()).isEqualTo(before + 1);
        assertThat(Arrays.asList(state("nurse_reserve", nursing), state("order", meal))).containsExactlyInAnyOrder("Y", "N");
    }

    @Test void concurrentCreditAndDebitBothSurvive() throws Exception {
        long nursing = source("nurse_reserve", "20.00"), before = consumptionCount();
        List<JsonNode> results = contend("elder", 4L, Arrays.asList(
                () -> call("/depositRecharge/recharge", newRecharge("10.00")), () -> execute("nurse_reserve", nursing)));
        assertThat(results).allSatisfy(result -> assertThat(result.path("code").asInt()).isEqualTo(200));
        assertBalance("90.00");
        assertThat(consumptionCount()).isEqualTo(before + 1);
    }

    @Test void concurrentCreditsCannotExceedBalanceCapacity() throws Exception {
        jdbc.update("UPDATE elder SET balance=99999999.98 WHERE id=4");
        List<JsonNode> results = contend("elder", 4L, Arrays.asList(
                () -> call("/depositRecharge/recharge", newRecharge("0.01")),
                () -> call("/depositRecharge/recharge", newRecharge("0.01"))));
        assertThat(results.stream().map(result -> result.path("code").asInt())).containsExactlyInAnyOrder(200, 409);
        assertBalance("99999999.99");
    }

    @Test void profileUpdatesCannotWriteBackAStaleBalance() {
        Elder stale = elders.selectById(4L);
        assertThat(call("/depositRecharge/recharge", newRecharge("10.00")).path("code").asInt()).isEqualTo(200);
        stale.setName("资金回归");
        elders.updateById(stale);
        assertBalance("110.00");
        assertThat(elders.selectById(4L).getName()).isEqualTo("资金回归");
    }

    @ParameterizedTest @ValueSource(strings={"nurse_reserve", "order"})
    void negativeHistoricalChargesCannotIncreaseTheBalance(String table) {
        long id = source(table, "-10.00"), before = consumptionCount();
        assertThat(execute(table, id).path("code").asInt()).isEqualTo(400);
        assertBalance("100.00");
        assertThat(state(table, id)).isEqualTo("N");
        assertThat(consumptionCount()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"nurse_reserve", "order"})
    void ledgerFailureRollsBackBalanceAndCompletionThenRetrySucceeds(String table) {
        long id = source(table, "10.00"), before = consumptionCount();
        // Fault injection is in the disposable database, not in the application under test.
        jdbc.execute("CREATE TRIGGER billing_test_reject_consume BEFORE INSERT ON consume FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic ledger failure'");
        try {
            assertThat(execute(table, id).path("code").asInt()).isNotEqualTo(200);
            assertBalance("100.00");
            assertThat(state(table, id)).isEqualTo("N");
            assertThat(consumptionCount()).isEqualTo(before);
        } finally { jdbc.execute("DROP TRIGGER billing_test_reject_consume"); }
        assertThat(execute(table, id).path("code").asInt()).isEqualTo(200);
        assertBalance("90.00");
        assertThat(state(table, id)).isEqualTo("Y");
        assertThat(consumptionCount()).isEqualTo(before + 1);
    }

    @Test void zeroCostOrderStillCompletesExactlyOnce() {
        long id = source("order", "0.00"), before = consumptionCount();
        assertThat(execute("order", id).path("code").asInt()).isEqualTo(200);
        assertThat(execute("order", id).path("msg").asText()).isEqualTo("该订单已完成");
        assertBalance("100.00");
        assertThat(consumptionCount()).isEqualTo(before + 1);
    }

    @Test void creditBeyondBalanceCapacityIsAnExplicitConflict() {
        jdbc.update("UPDATE elder SET balance=99999999.98 WHERE id=4");
        assertThat(call("/depositRecharge/recharge", newRecharge("0.02")).path("code").asInt()).isEqualTo(409);
        assertBalance("99999999.98");
    }

    @Test void nursingPriceAndSnapshotComeFromTheSelectedCatalogEntry() {
        long catalog = insert("INSERT INTO service_item(type_id,name,charge_method,price,need_date,del_flag,create_id,create_time,update_id,update_time) VALUES(1,'资金回归','按次',12.50,10,'N',1,NOW(),1,NOW())");
        long before = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM nurse_reserve", Long.class);
        JsonNode result = post("/nurseReserve/addNurseReserve", map("elderId", 4, "serviceId", catalog, "frequency", 2,
                "serviceName", "伪报价", "needDate", 1, "servicePrice", "-100", "chargeMethod", "按月", "payAmount", "-200"));
        assertThat(result.path("code").asInt()).isEqualTo(200);
        Map<String,Object> saved = jdbc.queryForMap("SELECT service_name,service_price,charge_method,need_date,pay_amount FROM nurse_reserve WHERE id>? ORDER BY id DESC LIMIT 1", before);
        assertThat(saved.get("service_name")).isEqualTo("资金回归");
        assertThat((BigDecimal) saved.get("service_price")).isEqualByComparingTo("12.50");
        assertThat((BigDecimal) saved.get("pay_amount")).isEqualByComparingTo("25.00");
        assertThat(saved.get("charge_method")).isEqualTo("按次");
        assertThat(saved.get("need_date")).isEqualTo(10);
    }

    @Test void invalidMealQuantitiesCreateNoOrders() {
        long dish = jdbc.queryForObject("SELECT MIN(id) FROM dishes WHERE del_flag='N'", Long.class);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Long.class);
        for (Object lines : Arrays.asList(Collections.emptyList(), Collections.singletonList(map("dishesId", dish, "orderNum", -1)),
                Collections.singletonList(map("dishesId", dish, "orderNum", 0)),
                Collections.singletonList(map("dishesId", dish, "orderNum", 1.5)),
                Collections.singletonList(map("dishesId", dish, "orderNum", 2147483648L)),
                Arrays.asList(map("dishesId", dish, "orderNum", Integer.MAX_VALUE), map("dishesId", dish, "orderNum", 1)),
                Collections.singletonList(map("dishesId", 99999999, "orderNum", 1)))) {
            assertThat(post("/order/addOrder", map("elderId", 4, "dineDate", "2026-10-18", "dineType", "送餐", "orderDishesList", lines)).path("code").asInt()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Long.class)).isEqualTo(before);
    }

    @Test void validMealLinesAreCombinedAndChargedUsingCatalogPrices() {
        jdbc.update("UPDATE elder SET catering_set_id=NULL WHERE id=4");
        long dish = jdbc.queryForObject("SELECT MIN(id) FROM dishes WHERE del_flag='N' AND price>0", Long.class);
        BigDecimal price = jdbc.queryForObject("SELECT price FROM dishes WHERE id=?", BigDecimal.class, dish);
        long before = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM `order`", Long.class);
        assertThat(post("/order/addOrder", map("elderId", 4, "dineDate", "2026-10-18", "dineType", "送餐", "orderDishesList",
                Arrays.asList(map("dishesId", dish, "orderNum", 1), map("dishesId", dish, "orderNum", 2)))).path("code").asInt()).isEqualTo(200);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM `order` WHERE id>?", Long.class, before);
        BigDecimal total = price.multiply(new BigDecimal("3"));
        assertThat(jdbc.queryForObject("SELECT pay_amount FROM `order` WHERE id=?", BigDecimal.class, id)).isEqualByComparingTo(total);
        assertThat(jdbc.queryForObject("SELECT order_num FROM order_dishes WHERE order_id=?", Integer.class, id)).isEqualTo(3);
        assertThat(execute("order", id).path("code").asInt()).isEqualTo(200);
        assertBalance(new BigDecimal("100.00").subtract(total).toPlainString());
    }

    @Test void nursingRequiresAnActivePerVisitServiceAndPositiveQuantity() {
        long once = insert("INSERT INTO service_item(type_id,name,charge_method,price,need_date,del_flag,create_id,create_time,update_id,update_time) VALUES(1,'资金校验','按次',10,10,'N',1,NOW(),1,NOW())");
        long monthly = insert("INSERT INTO service_item(type_id,name,charge_method,price,need_date,del_flag,create_id,create_time,update_id,update_time) VALUES(1,'资金月费','按月',10,10,'N',1,NOW(),1,NOW())");
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM nurse_reserve", Long.class);
        for (Map<String,Object> values : Arrays.asList(map("serviceId", once, "frequency", 0), map("serviceId", once, "frequency", -1),
                map("serviceId", once, "frequency", 2.5), map("serviceId", once, "frequency", 2147483648L),
                map("serviceId", once), map("frequency", 1), map("serviceId", 99999999, "frequency", 1), map("serviceId", monthly, "frequency", 1))) {
            values.put("elderId", 4);
            assertThat(post("/nurseReserve/addNurseReserve", values).path("code").asInt()).isEqualTo(400);
        }
        jdbc.update("UPDATE service_item SET del_flag='Y' WHERE id=?", once);
        assertThat(post("/nurseReserve/addNurseReserve", map("elderId", 4, "serviceId", once, "frequency", 1)).path("code").asInt()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nurse_reserve", Long.class)).isEqualTo(before);
    }

    private List<JsonNode> contend(String table, long id, List<Callable<JsonNode>> calls) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(calls.size());
        List<Future<JsonNode>> results = new ArrayList<>();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("SELECT id FROM `" + table + "` WHERE id=? FOR UPDATE")) {
                lock.setLong(1, id);
                try (ResultSet row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
                for (Callable<JsonNode> call : calls) results.add(workers.submit(call));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                long waiting = 0;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks b ON b.ENGINE=w.ENGINE AND b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID WHERE b.OBJECT_SCHEMA=DATABASE() AND b.OBJECT_NAME=? AND b.INDEX_NAME='PRIMARY' AND b.LOCK_DATA=?", Long.class, table, String.valueOf(id));
                    if (waiting >= calls.size()) break;
                    Thread.sleep(50);
                }
                assertThat(waiting).as("all HTTP requests must reach the database lock barrier").isGreaterThanOrEqualTo(calls.size());
            } finally { blocker.rollback(); }
            List<JsonNode> responses = new ArrayList<>();
            for (Future<JsonNode> result : results) responses.add(result.get(30, TimeUnit.SECONDS));
            return responses;
        } finally { workers.shutdownNow(); workers.awaitTermination(30, TimeUnit.SECONDS); }
    }

    private long source(String table, String price) {
        if (table.equals("nurse_reserve")) return insert("INSERT INTO nurse_reserve(elder_id,service_name,need_date,service_price,charge_method,frequency,pay_amount,order_flag,create_id,create_time,update_id,update_time) VALUES(4,'资金回归',10,?,'按次',1,?,'N',1,NOW(),1,NOW())", price, price);
        return insert("INSERT INTO `order`(elder_id,dine_date,dine_type,pay_amount,order_flag,create_id,create_time,update_id,update_time) VALUES(4,NOW(),'送餐',?,'N',1,NOW(),1,NOW())", price);
    }

    private long insert(String sql, Object... values) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private JsonNode execute(String table, long id) {
        return table.equals("nurse_reserve") ? call("/nurseReserve/executeNurseReserve", map("id", id, "staffId", 4, "nurseDate", "2026-10-18"))
                : call("/order/sendOrder", map("id", id, "staffId", 4, "deliverDishesDate", "2026-10-18"));
    }
    private String state(String table, long id) { return jdbc.queryForObject("SELECT order_flag FROM `" + table + "` WHERE id=?", String.class, id); }
    private long consumptionCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM consume WHERE elder_id=4", Long.class); }
    private void assertBalance(String value) { assertThat(jdbc.queryForObject("SELECT balance FROM elder WHERE id=4", BigDecimal.class)).isEqualByComparingTo(value); }
    private JsonNode call(String path, Object body) { return request(HttpMethod.PUT, path, body); }
    private Map<String, Object> newRecharge(String amount) {
        return map("elderId", 4, "amount", amount, "requestId", UUID.randomUUID().toString());
    }
    private JsonNode post(String path, Object body) { return request(HttpMethod.POST, path, body); }
    private JsonNode request(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("token", token);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
    }
    private Map<String,Object> map(Object... values) {
        Map<String,Object> result = new HashMap<>(); for (int index=0; index<values.length; index+=2) result.put((String) values[index], values[index+1]); return result;
    }
}
