package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Disposable MySQL/Redis integration: real background transactions and authenticated HTTP. */
@EnabledIfEnvironmentVariable(named="ALERT_HTTP_TEST", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}", "spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
        "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}", "spring.redis.password=scope-redis-only",
        "ai.rag.enabled=false", "ai.provider.enabled=false", "security.password-reset.email-enabled=false",
        "spring.quartz.auto-startup=false", "ai.alert.scan.enabled=false"})
class AlertAutomationHttpIntegrationTest {
    private static final long ROLE=868000, NO_MENU_ROLE=868010, STAFF=868001, SECOND=868002,
            OUTSIDER=868003, NO_MENU=868004, ELDER=868101, EXIT_ELDER=868102;
    private static final String PASSWORD="automation-fixture-only";
    private static final String CHECKPOINT="ai_alert_scan_checkpoint";
    private static final String RECEIPT="ai_alert_read_receipt";
    private static final String WORKER="com.shanzhu.beadhouse.service.common.CareAlertScanWorker";
    private static final String SCHEDULER="com.shanzhu.beadhouse.service.common.CareAlertScanScheduler";
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    private String staffToken, secondToken, outsiderToken, noMenuToken;
    private LocalDate yesterday;
    private long planBase;
    private boolean created, checkpointSaved;
    private Map<String,Object> savedCheckpoint;
    private Object worker;
    private Object originalClock;

    @BeforeEach void seedOwnedFixtures() {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false); factory.setConnectTimeout(10000); factory.setReadTimeout(40000);
        http.getRestTemplate().setRequestFactory(factory);
        assertThat(count("SELECT COUNT(*) FROM staff WHERE id BETWEEN ? AND ?",STAFF,NO_MENU)).isZero();
        assertThat(count("SELECT COUNT(*) FROM elder WHERE id IN (?,?)",ELDER,EXIT_ELDER)).isZero();
        assertThat(count("SELECT COUNT(*) FROM role WHERE id IN (?,?)",ROLE,NO_MENU_ROLE)).isZero();
        // Keep the cursor above every unrelated plan, including fixtures left by older suites.
        planBase=Math.max(868500L,number("SELECT COALESCE(MAX(id),0)+1 FROM medication_plan"));
        assertThat(count("SELECT COUNT(*) FROM medication_plan WHERE id BETWEEN ? AND ?",planBase,planBase+299)).isZero();
        created=true;
        for(long role:Arrays.asList(ROLE,NO_MENU_ROLE))
            jdbc.update("INSERT INTO role(id,name,create_id,create_time,update_id,update_time) VALUES(?,'auto-role',1,NOW(),1,NOW())",role);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) "
                +"SELECT ?,id,1,NOW(),1,NOW() FROM auth WHERE url IN ('/ai/daily/index','/ai/medication/index')",ROLE);
        assertThat(count("SELECT COUNT(*) FROM role_auth WHERE role_id=?",ROLE)).isEqualTo(2);
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        for(long staff:Arrays.asList(STAFF,SECOND,OUTSIDER,NO_MENU)) {
            jdbc.update("INSERT INTO staff(id,role_id,name,id_num,age,sex,phone,email,pass,avator,address,leave_flag,"
                            +"create_id,create_time,update_id,update_time) VALUES(?,?,'auto-staff',?,30,'女',?,'auto@example.invalid',?,'','fixture','N',1,NOW(),1,NOW())",
                    staff,staff==NO_MENU?NO_MENU_ROLE:ROLE,"990101199001"+staff,"19900"+staff,hash);
        }
        for(long elder:Arrays.asList(ELDER,EXIT_ELDER))
            jdbc.update("INSERT INTO elder(id,name,id_num,age,sex,phone,address,balance,check_flag,"
                            +"create_id,create_time,update_id,update_time) VALUES(?,'auto-elder',?,70,'女','19900868101','fixture',0,?,?,NOW(),?,NOW())",
                    elder,"990101195001"+elder,elder==ELDER?"入住":"已退住",STAFF,STAFF);
        for(long staff:Arrays.asList(STAFF,SECOND,NO_MENU))
            jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active,create_id,update_id) VALUES(?,?,'Y',?,?)",ELDER,staff,STAFF,STAFF);
        yesterday=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        staffToken=login(STAFF); secondToken=login(SECOND); outsiderToken=login(OUTSIDER); noMenuToken=login(NO_MENU);
    }

    @AfterEach void cleanOnlyOwnedFixturesAndRestoreCheckpoint() {
        if(!created) return;
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS alert_automation_owned_failure");
            if(worker!=null && originalClock!=null) ReflectionTestUtils.setField(worker,"clock",originalClock);
            if(checkpointSaved) {
                jdbc.update("DELETE FROM "+CHECKPOINT+" WHERE id=1");
                if(savedCheckpoint!=null) {
                    String columns=String.join(",",savedCheckpoint.keySet());
                    jdbc.update("INSERT INTO "+CHECKPOINT+"("+columns+") VALUES("
                            +String.join(",",Collections.nCopies(savedCheckpoint.size(),"?"))+")",savedCheckpoint.values().toArray());
                }
            }
            if(tableExists(RECEIPT))
                jdbc.update("DELETE r FROM "+RECEIPT+" r JOIN ai_care_alert a ON a.id=r.alert_id WHERE a.elder_id IN (?,?)",ELDER,EXIT_ELDER);
            jdbc.update("DELETE FROM ai_daily_task WHERE elder_id IN (?,?)",ELDER,EXIT_ELDER);
            jdbc.update("DELETE FROM ai_care_alert WHERE elder_id IN (?,?)",ELDER,EXIT_ELDER);
            jdbc.update("DELETE FROM medication_execution WHERE elder_id IN (?,?)",ELDER,EXIT_ELDER);
            jdbc.update("DELETE FROM medication_plan WHERE elder_id IN (?,?)",ELDER,EXIT_ELDER);
            jdbc.update("DELETE FROM elder_staff_assignment WHERE elder_id IN (?,?)",ELDER,EXIT_ELDER);
            jdbc.update("DELETE FROM elder WHERE id IN (?,?) AND create_id=?",ELDER,EXIT_ELDER,STAFF);
            for(String token:Arrays.asList(staffToken,secondToken,outsiderToken,noMenuToken))
                if(token!=null) request(HttpMethod.DELETE,"/account/logout",token,null);
            jdbc.update("DELETE FROM ai_audit_log WHERE operator_id BETWEEN ? AND ?",STAFF,NO_MENU);
            jdbc.update("DELETE FROM role_auth WHERE role_id IN (?,?)",ROLE,NO_MENU_ROLE);
            jdbc.update("DELETE FROM staff WHERE id BETWEEN ? AND ? AND role_id IN (?,?)",STAFF,NO_MENU,ROLE,NO_MENU_ROLE);
            jdbc.update("DELETE FROM role WHERE id IN (?,?)",ROLE,NO_MENU_ROLE);
        } finally { created=false; }
    }

    @Test void countAndCursorPaginationIncludeEveryOneOf205Notifications() {
        for(int i=0;i<205;i++) seedAlert("notification-"+i,planBase+i);
        JsonNode first=get("/ai/daily/notifications?afterId=0&limit=20",staffToken);
        ok(first);
        assertThat(first.path("data").path("unreadCount").asLong()).isEqualTo(205);
        assertThat(first.path("data").path("items").size()).isEqualTo(20);
        assertThat(unread(staffToken)).isEqualTo(205);
        Set<Long> visited=new HashSet<>();
        JsonNode page=first;
        long cursor=0;
        for(int safety=0;safety<12;safety++) {
            for(JsonNode row:page.path("data").path("items")) {
                assertThat(row.path("elderId").asLong()).isEqualTo(ELDER);
                assertThat(row.path("elderName").asText()).isEqualTo("auto-elder");
                assertThat(row.path("noticeRevision").asLong()).isPositive();
                assertThat(visited.add(row.path("id").asLong())).as("cursor pages must not repeat rows").isTrue();
            }
            if(!page.path("data").path("hasMore").asBoolean()) break;
            long next=page.path("data").path("nextCursor").asLong();
            assertThat(next).isPositive().isNotEqualTo(cursor);
            cursor=next;
            page=get("/ai/daily/notifications?afterId="+cursor+"&limit=20",staffToken); ok(page);
        }
        assertThat(visited).hasSize(205);
        assertThat(page.path("data").path("hasMore").asBoolean()).isFalse();
        assertThat(get("/ai/daily/alerts?state=ALL",staffToken).path("data").size()).isEqualTo(200);
    }

    @Test void readReceiptIsPersonalAndForgedStaffIdCannotMarkAnotherEmployeeRead() {
        long id=seedAlert("personal-read",planBase);
        JsonNode notice=firstNotice(staffToken);
        long revision=notice.path("noticeRevision").asLong();
        JsonNode forged=post("/ai/daily/notifications/read",staffToken,map("id",id,"noticeRevision",revision,"staffId",SECOND));
        assertThat(forged.path("code").asInt()).isIn(200,400);
        assertThat(unread(secondToken)).isEqualTo(1);
        ok(post("/ai/daily/notifications/read",staffToken,map("id",id,"noticeRevision",revision)));
        ok(post("/ai/daily/notifications/read",staffToken,map("id",id,"noticeRevision",revision)));
        assertThat(unread(staffToken)).isZero();
        assertThat(unread(secondToken)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM ai_care_alert WHERE id=?",String.class,id)).isEqualTo("OPEN");
        assertThat(count("SELECT COUNT(*) FROM "+RECEIPT+" WHERE staff_id=? AND alert_id=?",SECOND,id)).isZero();
    }

    @Test void revocationAndMissingMenuDenyListCountReadAndCannotUseForgedIdentity() {
        long id=seedAlert("scope-notification",planBase);
        long revision=firstNotice(staffToken).path("noticeRevision").asLong();
        for(String path:Arrays.asList("/ai/daily/notifications","/ai/daily/notification-count")) {
            assertThat(get(path,noMenuToken).path("code").asInt()).isEqualTo(403);
            assertThat(get(path,null).path("code").asInt()).isIn(401,403);
        }
        assertThat(post("/ai/daily/notifications/read",noMenuToken,map("id",id,"noticeRevision",revision)).path("code").asInt()).isEqualTo(403);
        assertThat(unread(outsiderToken)).isZero();
        JsonNode outside=get("/ai/daily/notifications?staffId="+STAFF,outsiderToken); ok(outside);
        assertThat(outside.path("data").path("items").size()).isZero();
        assertThat(post("/ai/daily/notifications/read",outsiderToken,map("id",id,"noticeRevision",revision,"staffId",STAFF)).path("code").asInt()).isIn(400,403);
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE elder_id=? AND staff_id=?",ELDER,STAFF);
        assertThat(unread(staffToken)).isZero();
        JsonNode revoked=get("/ai/daily/notifications",staffToken); ok(revoked);
        assertThat(revoked.path("data").path("items").size()).isZero();
        assertThat(post("/ai/daily/notifications/read",staffToken,map("id",id,"noticeRevision",revision)).path("code").asInt()).isEqualTo(403);
        assertThat(post("/ai/daily/alerts/scan?date="+yesterday,null,null).path("code").asInt()).isIn(401,403);
    }

    @Test void evidenceAToBToAHasNewVersionsAndOldReadCannotConsumeNewNotification() {
        insertPlan(planBase,ELDER,yesterday,yesterday,"Y");
        execute("SKIPPED","证据 A");
        JsonNode a=firstNotice(staffToken);
        long id=a.path("id").asLong(), initial=a.path("noticeRevision").asLong();
        ok(post("/ai/daily/notifications/read",staffToken,map("id",id,"noticeRevision",initial)));
        assertThat(unread(staffToken)).isZero();
        execute("SKIPPED","证据 B");
        long second=firstNotice(staffToken).path("noticeRevision").asLong();
        assertThat(second).isGreaterThan(initial);
        JsonNode stale=post("/ai/daily/notifications/read",staffToken,map("id",id,"noticeRevision",initial));
        assertThat(stale.path("code").asInt()).isEqualTo(409);
        assertThat(unread(staffToken)).isEqualTo(1);
        ok(post("/ai/daily/notifications/read",staffToken,map("id",id,"noticeRevision",second)));
        execute("SKIPPED","证据 A");
        assertThat(firstNotice(staffToken).path("noticeRevision").asLong()).isGreaterThan(second);
        assertThat(unread(staffToken)).isEqualTo(1);
        execute("SKIPPED","证据 A");
        assertThat(jdbc.queryForObject("SELECT notice_revision FROM ai_care_alert WHERE id=?",Long.class,id)).isEqualTo(second+1);
    }

    @Test void repeatedBackgroundEvidencePreservesAcknowledgedResolvedAndDoneTask() throws Exception {
        insertPlan(planBase,ELDER,yesterday,yesterday,"Y");
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        long token=claim(worker()); batch(worker(),token);
        JsonNode alert=firstNotice(staffToken);
        long id=alert.path("id").asLong(), notice=alert.path("noticeRevision").asLong();
        long revision=number("SELECT revision FROM ai_care_alert WHERE id=?",id);
        ok(post("/ai/daily/alerts/ack",staffToken,map("id",id,"revision",revision)));
        resetCheckpoint(yesterday,yesterday,planBase-1);
        batch(worker(),claim(worker()));
        assertThat(jdbc.queryForObject("SELECT state FROM ai_care_alert WHERE id=?",String.class,id)).isEqualTo("ACKNOWLEDGED");
        revision=number("SELECT revision FROM ai_care_alert WHERE id=?",id);
        ok(post("/ai/daily/alerts/resolve",staffToken,map("id",id,"revision",revision,"note","人工已完成核对")));
        jdbc.update("UPDATE ai_daily_task SET state='DONE' WHERE elder_id=?",ELDER);
        resetCheckpoint(yesterday,yesterday,planBase-1); batch(worker(),claim(worker()));
        assertThat(jdbc.queryForObject("SELECT state FROM ai_care_alert WHERE id=?",String.class,id)).isEqualTo("RESOLVED");
        assertThat(number("SELECT notice_revision FROM ai_care_alert WHERE id=?",id)).isEqualTo(notice);
        assertThat(count("SELECT COUNT(*) FROM ai_daily_task WHERE elder_id=? AND state<>'DONE'",ELDER)).isZero();
        execute("DONE",null);
        long doneRevision=number("SELECT notice_revision FROM ai_care_alert WHERE id=?",id);
        assertThat(doneRevision).isGreaterThan(notice);
        resetCheckpoint(yesterday,yesterday,planBase-1); batch(worker(),claim(worker()));
        assertThat(jdbc.queryForObject("SELECT state FROM ai_care_alert WHERE id=?",String.class,id)).isEqualTo("RESOLVED");
        assertThat(number("SELECT notice_revision FROM ai_care_alert WHERE id=?",id)).isEqualTo(doneRevision);
    }

    @Test void backgroundPagesAll205PlansWithoutLoginAndCountsBeyondDisplayLimit() throws Exception {
        for(int i=0;i<205;i++) insertPlan(planBase+i,ELDER,yesterday,yesterday,"Y");
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        Object scan=worker(); long token=claim(scan);
        assertThat(batch(scan,token)).isEqualTo(100);
        assertThat(ownedAlerts()).isEqualTo(100);
        assertThat(batch(scan,token)).isEqualTo(100);
        assertThat(batch(scan,token)).isEqualTo(5);
        assertThat(ownedAlerts()).isEqualTo(205);
        assertThat(count("SELECT COUNT(*) FROM ai_daily_task WHERE elder_id=?",ELDER)).isEqualTo(205);
        assertThat(count("SELECT COUNT(*) FROM medication_execution WHERE elder_id=?",ELDER)).isZero();
        assertThat(unread(staffToken)).isEqualTo(205);
        assertThat(batch(scan,token)).isEqualTo(-1);
    }

    @Test void unchangedBackgroundEvidenceDoesNotInvalidateAnInProgressTaskCompletion() throws Exception {
        insertPlan(planBase,ELDER,yesterday,yesterday,"Y");
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        assertThat(batch(worker(),claim(worker()))).isEqualTo(1);
        JsonNode tasks=get("/ai/daily/tasks?state=ALL",staffToken); ok(tasks);
        assertThat(tasks.path("data").size()).isEqualTo(1);
        JsonNode task=tasks.path("data").get(0);
        assertThat(task.path("taskKey").asText()).isEqualTo(key(planBase));
        long id=task.path("id").asLong();
        JsonNode claimed=post("/ai/daily/tasks/claim",staffToken,
                map("id",id,"revision",task.path("revision").asInt()));
        ok(claimed);
        long capturedRevision=claimed.path("data").path("revision").asLong();
        assertThat(claimed.path("data").path("ownerId").asLong()).isEqualTo(STAFF);
        assertThat(claimed.path("data").path("state").asText()).isEqualTo("CLAIMED");

        // A user keeps this revision while writing a review note. A periodic
        // scan with identical evidence is not a concurrent business change.
        resetCheckpoint(yesterday,yesterday,planBase-1);
        assertThat(batch(worker(),claim(worker()))).isEqualTo(1);
        assertThat(number("SELECT revision FROM ai_daily_task WHERE id=?",id))
                .as("unchanged evidence must preserve the task revision captured by its owner").isEqualTo(capturedRevision);
        assertThat(number("SELECT owner_id FROM ai_daily_task WHERE id=?",id)).isEqualTo(STAFF);
        assertThat(jdbc.queryForObject("SELECT state FROM ai_daily_task WHERE id=?",String.class,id)).isEqualTo("CLAIMED");
        String note="已核对缺少登记的原因，实际用药情况仍按执行记录核实";
        ok(post("/ai/daily/tasks/complete",staffToken,map("id",id,"revision",capturedRevision,"note",note)));
        assertThat(jdbc.queryForObject("SELECT state FROM ai_daily_task WHERE id=?",String.class,id)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT completion_note FROM ai_daily_task WHERE id=?",String.class,id)).isEqualTo(note);
        assertThat(number("SELECT revision FROM ai_daily_task WHERE id=?",id)).isEqualTo(capturedRevision+1);
        assertThat(count("SELECT COUNT(*) FROM medication_execution WHERE elder_id=?",ELDER)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM ai_care_alert WHERE alert_key=?",String.class,key(planBase))).isEqualTo("OPEN");
    }

    @Test void secondBatchFailureRollsBackAlertsTasksAndCursorThenAnotherWorkerResumes() throws Exception {
        for(int i=0;i<205;i++) insertPlan(planBase+i,ELDER,yesterday,yesterday,"Y");
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        Object first=worker(); long token=claim(first);
        assertThat(batch(first,token)).isEqualTo(100);
        long cursor=number("SELECT plan_cursor FROM "+CHECKPOINT+" WHERE id=1");
        jdbc.execute("CREATE TRIGGER alert_automation_owned_failure BEFORE INSERT ON ai_care_alert FOR EACH ROW "
                +"BEGIN IF NEW.elder_id="+ELDER+" AND NEW.source_id="+(planBase+120)+" THEN "
                +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned second batch failure'; END IF; END");
        assertThatThrownBy(() -> batch(first,token)).hasStackTraceContaining("owned second batch failure");
        assertThat(ownedAlerts()).isEqualTo(100);
        assertThat(count("SELECT COUNT(*) FROM ai_daily_task WHERE elder_id=?",ELDER)).isEqualTo(100);
        assertThat(number("SELECT plan_cursor FROM "+CHECKPOINT+" WHERE id=1")).isEqualTo(cursor);
        jdbc.execute("DROP TRIGGER alert_automation_owned_failure");
        jdbc.update("UPDATE "+CHECKPOINT+" SET lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=1");
        Object restarted=newWorker(); long replacement=claim(restarted);
        assertThat(replacement).isGreaterThan(token);
        assertThat(batch(restarted,replacement)).isEqualTo(100);
        assertThat(batch(restarted,replacement)).isEqualTo(5);
        assertThat(ownedAlerts()).isEqualTo(205);
    }

    @Test void twoRealWorkersRaceForOneLeaseAndExpiredOwnerCannotAdvanceCheckpoint() throws Exception {
        insertPlan(planBase,ELDER,yesterday,yesterday,"Y");
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        Object first=worker(), second=newWorker();
        CountDownLatch ready=new CountDownLatch(2), go=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Long> a=pool.submit(() -> {ready.countDown(); go.await(); return (Long)invoke(first,"claimLease");});
            Future<Long> b=pool.submit(() -> {ready.countDown(); go.await(); return (Long)invoke(second,"claimLease");});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); go.countDown();
            Long at=a.get(10,TimeUnit.SECONDS), bt=b.get(10,TimeUnit.SECONDS);
            assertThat((at==null)^(bt==null)).as("exactly one worker acquires the lease").isTrue();
            Object winner=at!=null?first:second, loser=at!=null?second:first;
            long old=at!=null?at:bt;
            jdbc.update("UPDATE "+CHECKPOINT+" SET lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=1");
            long fresh=claim(loser); assertThat(fresh).isGreaterThan(old);
            assertThatThrownBy(() -> batch(winner,old)).isInstanceOf(IllegalStateException.class);
            assertThat(ownedAlerts()).isZero();
            assertThat(number("SELECT plan_cursor FROM "+CHECKPOINT+" WHERE id=1")).isEqualTo(planBase-1);
            assertThat(batch(loser,fresh)).isEqualTo(1);
            assertThat(ownedAlerts()).isEqualTo(1);
        } finally { go.countDown(); pool.shutdownNow(); }
    }

    @Test void pausedHistoricalDateResumesWithoutSkippingOrScanningTodayAndExitedElders() throws Exception {
        LocalDate stalled=yesterday.minusDays(40);
        insertPlan(planBase,ELDER,stalled,yesterday.plusDays(1),"N");
        insertPlan(planBase+1,EXIT_ELDER,stalled,yesterday.plusDays(1),"Y");
        prepareCheckpoint(stalled,stalled,planBase-1);
        long token=claim(worker());
        assertThat(jdbc.queryForObject("SELECT scan_date FROM "+CHECKPOINT+" WHERE id=1",String.class)).isEqualTo(stalled.toString());
        assertThat(batch(worker(),token)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ai_care_alert WHERE elder_id=?",EXIT_ELDER)).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_care_alert WHERE elder_id=? AND alert_key LIKE ?",ELDER,stalled+"-%")).isEqualTo(1);
        resetCheckpoint(yesterday.plusDays(1),yesterday.plusDays(1),planBase-1);
        long todayToken=claim(worker());
        batch(worker(),todayToken);
        assertThat(count("SELECT COUNT(*) FROM ai_care_alert WHERE elder_id=? AND alert_key LIKE ?",ELDER,yesterday.plusDays(1)+"-%")).isZero();
    }

    @Test void firstCatchupUsesShanghaiDateAcrossUtcMidnightBoundary() throws Exception {
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        Object scan=worker();
        jdbc.update("DELETE FROM "+CHECKPOINT+" WHERE id=1");
        ReflectionTestUtils.setField(scan,"clock",Clock.fixed(Instant.parse("2026-01-01T15:59:59Z"),ZoneOffset.UTC));
        claim(scan);
        assertThat(jdbc.queryForObject("SELECT target_date FROM "+CHECKPOINT+" WHERE id=1",String.class)).isEqualTo("2025-12-31");
        assertThat(jdbc.queryForObject("SELECT scan_date FROM "+CHECKPOINT+" WHERE id=1",String.class)).isEqualTo("2025-12-01");
        jdbc.update("DELETE FROM "+CHECKPOINT+" WHERE id=1");
        ReflectionTestUtils.setField(scan,"clock",Clock.fixed(Instant.parse("2026-01-01T16:00:00Z"),ZoneOffset.UTC));
        claim(scan);
        assertThat(jdbc.queryForObject("SELECT target_date FROM "+CHECKPOINT+" WHERE id=1",String.class)).isEqualTo("2026-01-01");
        assertThat(jdbc.queryForObject("SELECT scan_date FROM "+CHECKPOINT+" WHERE id=1",String.class)).isEqualTo("2025-12-02");
    }

    @Test void effectiveDatesIncludeDisabledHistoryAndPendingCheckoutButExcludeOutsideDates() throws Exception {
        insertPlan(planBase,ELDER,yesterday.minusDays(2),yesterday,"N");
        insertPlan(planBase+1,ELDER,yesterday.plusDays(1),yesterday.plusDays(1),"Y");
        insertPlan(planBase+2,ELDER,yesterday.minusDays(2),yesterday.minusDays(1),"N");
        jdbc.update("UPDATE elder SET check_flag='退住审核' WHERE id=?",EXIT_ELDER);
        insertPlan(planBase+3,EXIT_ELDER,yesterday,yesterday,"Y");
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        assertThat(batch(worker(),claim(worker()))).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM ai_care_alert WHERE source_id IN (?,?) AND elder_id IN (?,?)",
                planBase,planBase+3,ELDER,EXIT_ELDER)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM ai_care_alert WHERE source_id IN (?,?) AND elder_id=?",
                planBase+1,planBase+2,ELDER)).isZero();
        assertThat(count("SELECT COUNT(*) FROM medication_execution WHERE elder_id IN (?,?)",ELDER,EXIT_ELDER)).isZero();
    }

    @Test void anEmptyHistoricalDayAdvancesDurablyAndLeaseTakeoverKeepsTheNextDate() throws Exception {
        LocalDate empty=yesterday.minusDays(2);
        insertPlan(planBase,ELDER,yesterday,yesterday,"Y");
        prepareCheckpoint(empty,yesterday,planBase-1);
        long token=claim(worker());
        assertThat(batch(worker(),token)).isZero();
        assertThat(jdbc.queryForObject("SELECT scan_date FROM "+CHECKPOINT+" WHERE id=1",String.class)).isEqualTo(empty.plusDays(1).toString());
        assertThat(jdbc.queryForObject("SELECT last_completed_date FROM "+CHECKPOINT+" WHERE id=1",String.class)).isEqualTo(empty.toString());
        jdbc.update("UPDATE "+CHECKPOINT+" SET lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=1");
        long next=claim(newWorker());
        assertThat(next).isGreaterThan(token);
        assertThat(jdbc.queryForObject("SELECT scan_date FROM "+CHECKPOINT+" WHERE id=1",String.class)).isEqualTo(empty.plusDays(1).toString());
        assertThat(jdbc.queryForObject("SELECT target_date FROM "+CHECKPOINT+" WHERE id=1",String.class)).isEqualTo(yesterday.toString());
        assertThat(ownedAlerts()).isZero();
    }

    @Test void scanStatusRequiresTheMenuAndDoesNotExposeLeaseOrRawFailureDetails() throws Exception {
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        claim(worker());
        jdbc.update("UPDATE "+CHECKPOINT+" SET last_error='fixture-private-SQL-diagnostic' WHERE id=1");
        JsonNode status=get("/ai/daily/scan-status",staffToken); ok(status);
        assertThat(status.path("data").isObject()).isTrue();
        assertThat(status.path("data").path("enabled").isBoolean()).isTrue();
        assertThat(status.path("data").path("enabled").asBoolean()).isFalse();
        assertThat(status.path("data").path("state").isTextual()).isTrue();
        for(String key:Arrays.asList("scanDate","targetDate","lastCompletedDate","lastSuccessAt")) {
            JsonNode value=status.path("data").path(key);
            assertThat(value.isNull() || value.isTextual()).as("safe date field "+key).isTrue();
        }
        assertThat(status.path("data").toString()).doesNotContain("fixture-private-SQL-diagnostic");
        for(String key:Arrays.asList("leaseOwner","leaseToken","leaseUntil","planCursor",
                "lease_owner","lease_token","lease_until","plan_cursor"))
            assertThat(status.path("data").has(key)).as("public status must not expose "+key).isFalse();
        assertThat(get("/ai/daily/scan-status",noMenuToken).path("code").asInt()).isEqualTo(403);
        assertThat(get("/ai/daily/scan-status",null).path("code").asInt()).isIn(401,403);
    }

    @Test void productionScheduledMethodActuallyTriggersPersistedBackgroundWork() throws Exception {
        insertPlan(planBase,ELDER,yesterday,yesterday,"Y");
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        Object scan=worker();
        Class<?> schedulerType=Class.forName(SCHEDULER);
        // Register the production scheduler in a short-lived context. The application's
        // usual timer stays disabled, so no background tick can race another fixture.
        try(AnnotationConfigApplicationContext scheduled=new AnnotationConfigApplicationContext()) {
            scheduled.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-scheduler",map(
                    "ai.alert.scan.enabled","true","ai.alert.scan.initial-delay-ms","10","ai.alert.scan.delay-ms","50")));
            scheduled.register(SchedulingOnly.class);
            scheduled.getBeanFactory().registerSingleton("careAlertScanWorker",scan);
            scheduled.register(schedulerType);
            scheduled.refresh();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(ownedAlerts()==0 && System.nanoTime()<deadline) Thread.sleep(25);
            assertThat(ownedAlerts()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_success_at FROM "+CHECKPOINT+" WHERE id=1",Object.class)).isNotNull();
        }
    }

    @Test void backgroundBatchDoesNotGapLockTheNextPlansExecutionInsert() throws Exception {
        insertPlan(planBase,ELDER,yesterday,yesterday,"Y");
        insertPlan(planBase+1,ELDER,yesterday,yesterday,"Y");
        long first=seedAlert(key(planBase),planBase);
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        Object scan=worker(); long token=claim(scan);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<Integer> scanning=null;
        try(Connection barrier=dataSource.getConnection()) {
            barrier.setAutoCommit(false);
            try {
                long connection;
                try(Statement statement=barrier.createStatement(); ResultSet row=statement.executeQuery("SELECT CONNECTION_ID()")) {
                    assertThat(row.next()).isTrue(); connection=row.getLong(1);
                }
                try(PreparedStatement statement=barrier.prepareStatement("SELECT id FROM ai_care_alert WHERE id=? FOR UPDATE")) {
                    statement.setLong(1,first); try(ResultSet row=statement.executeQuery()) { assertThat(row.next()).isTrue(); }
                }
                scanning=pool.submit(() -> batch(scan,token));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(count("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                        +"JOIN information_schema.innodb_trx b ON b.trx_id=w.BLOCKING_ENGINE_TRANSACTION_ID "
                        +"WHERE b.trx_mysql_thread_id=?",connection)==0 && System.nanoTime()<deadline) Thread.sleep(20);
                assertThat(count("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                        +"JOIN information_schema.innodb_trx b ON b.trx_id=w.BLOCKING_ENGINE_TRANSACTION_ID "
                        +"WHERE b.trx_mysql_thread_id=?",connection)).isPositive();
                Future<JsonNode> execution=pool.submit(() -> post("/ai/medication/execute",staffToken,
                        map("planId",planBase+1,"executionDate",yesterday.toString(),"period","早","status","DONE")));
                ok(execution.get(8,TimeUnit.SECONDS));
            } finally { barrier.rollback(); }
            assertThat(scanning.get(20,TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM medication_execution WHERE plan_id=?",planBase+1)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM ai_care_alert WHERE alert_key=? AND state<>'RESOLVED'",key(planBase+1))).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void leaseExpiryIsRecheckedAfterWaitingWhenEveryCandidateWasDischarged() throws Exception {
        insertPlan(planBase,ELDER,yesterday,yesterday,"Y");
        prepareCheckpoint(yesterday,yesterday,planBase-1);
        Object scan=worker();
        long token=claim(scan);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(Connection barrier=dataSource.getConnection()) {
            barrier.setAutoCommit(false);
            try {
                long connection;
                try(Statement statement=barrier.createStatement(); ResultSet row=statement.executeQuery("SELECT CONNECTION_ID()")) {
                    assertThat(row.next()).isTrue(); connection=row.getLong(1);
                }
                // The worker's nonlocking candidate query still sees the committed
                // 入住 state; its subsequent elder FOR UPDATE must wait for us.
                try(PreparedStatement statement=barrier.prepareStatement("UPDATE elder SET check_flag='已退住' WHERE id=?")) {
                    statement.setLong(1,ELDER); assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                jdbc.update("UPDATE "+CHECKPOINT+" SET lease_until=TIMESTAMPADD(SECOND,2,NOW(3)) WHERE id=1");
                Future<Integer> scanning=pool.submit(() -> batch(scan,token));
                String waitSql="SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                        +"JOIN information_schema.innodb_trx b ON b.trx_id=w.BLOCKING_ENGINE_TRANSACTION_ID "
                        +"JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                        +"AND l.ENGINE=w.ENGINE WHERE b.trx_mysql_thread_id=? AND l.OBJECT_NAME='elder'";
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(count(waitSql,connection)==0 && !scanning.isDone() && System.nanoTime()<deadline) Thread.sleep(10);
                assertThat(count(waitSql,connection))
                        .as("worker passed its first lease check and is waiting on the candidate elder").isPositive();
                // Observe database time crossing the real stored deadline, rather
                // than assuming that a host sleep implies MySQL lease expiry.
                String expiredSql="SELECT COUNT(*) FROM "+CHECKPOINT+" WHERE id=1 AND lease_until<=NOW(3)";
                while(count(expiredSql)==0 && System.nanoTime()<deadline) Thread.sleep(10);
                assertThat(count(expiredSql)).as("MySQL confirms the original lease has expired").isEqualTo(1);
                assertThat(count(waitSql,connection)).as("the worker remains blocked until checkout commits").isPositive();
                barrier.commit();
                // The candidate is now skipped without any mapper DML. MyBatis must
                // not reuse the earlier successful valid() SELECT from its local cache.
                assertThatThrownBy(() -> scanning.get(15,TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("租约已失效");
                assertThat(number("SELECT plan_cursor FROM "+CHECKPOINT+" WHERE id=1")).isEqualTo(planBase-1);
                assertThat(jdbc.queryForObject("SELECT scan_date FROM "+CHECKPOINT+" WHERE id=1",String.class))
                        .isEqualTo(yesterday.toString());
                assertThat(jdbc.queryForObject("SELECT last_success_at FROM "+CHECKPOINT+" WHERE id=1",Object.class)).isNull();
                assertThat(ownedAlerts()).isZero();
                assertThat(count("SELECT COUNT(*) FROM ai_daily_task WHERE elder_id=?",ELDER)).isZero();
            } finally { barrier.rollback(); }
        } finally {
            pool.shutdown();
            if(!pool.awaitTermination(20,TimeUnit.SECONDS)) {
                pool.shutdownNow();
                throw new AssertionError("The lease regression worker did not stop after releasing its row barrier");
            }
        }
    }

    @TestConfiguration @EnableScheduling
    static class SchedulingOnly {}

    private long seedAlert(String key,long source) {
        jdbc.update("INSERT INTO ai_care_alert(alert_key,elder_id,source_id,kind,detail,evidence_key,task_key) "
                +"VALUES(?,?,?,'MEDICATION_UNRECORDED','测试记录待核对',REPEAT('a',64),?)",key,ELDER,source,key);
        return number("SELECT id FROM ai_care_alert WHERE alert_key=?",key);
    }
    private void insertPlan(long id,long elder,LocalDate start,LocalDate end,String enabled) {
        jdbc.update("INSERT INTO medication_plan(id,elder_id,medicine_name,dose_instruction,periods,start_date,end_date,enabled,create_id,update_id) "
                +"VALUES(?,?,'auto-med','按已核对医嘱','早',?,?,?,?,?)",id,elder,start.toString(),end.toString(),enabled,STAFF,STAFF);
    }
    private void execute(String status,String note) {
        ok(post("/ai/medication/execute",staffToken,map("planId",planBase,"executionDate",yesterday.toString(),"period","早","status",status,"note",note)));
    }
    private void prepareCheckpoint(LocalDate date,LocalDate target,long cursor) {
        // Obtain worker first: old application fails with a clear absent-bean assertion,
        // rather than a missing migration table obscuring the red regression.
        worker();
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM "+CHECKPOINT+" WHERE id=1");
        savedCheckpoint=rows.isEmpty()?null:new LinkedHashMap<>(rows.get(0));
        checkpointSaved=true;
        resetCheckpoint(date,target,cursor);
    }
    private void resetCheckpoint(LocalDate date,LocalDate target,long cursor) {
        jdbc.update("DELETE FROM "+CHECKPOINT+" WHERE id=1");
        jdbc.update("INSERT INTO "+CHECKPOINT+"(id,scan_date,target_date,plan_cursor,lease_token,next_scan_at) VALUES(1,?,?,?,0,NOW())",
                date.toString(),target.toString(),cursor);
    }
    private Object worker() {
        if(worker==null) {
            try { worker=context.getBean(Class.forName(WORKER)); }
            catch(Exception error) { throw new AssertionError("A background worker independent of logged-in HTTP users must exist",error); }
            originalClock=ReflectionTestUtils.getField(worker,"clock");
        }
        return worker;
    }
    private Object newWorker() throws Exception {
        return context.getAutowireCapableBeanFactory().createBean(Class.forName(WORKER));
    }
    private Object invoke(Object target,String method,Object... arguments) throws Exception {
        try {
            return arguments.length==0?target.getClass().getMethod(method).invoke(target):
                    target.getClass().getMethod(method,Long.class).invoke(target,arguments);
        } catch(InvocationTargetException exception) {
            Throwable cause=exception.getCause();
            if(cause instanceof Exception) throw (Exception)cause;
            if(cause instanceof Error) throw (Error)cause;
            throw exception;
        }
    }
    private long claim(Object scan) throws Exception {
        Long token=(Long)invoke(scan,"claimLease"); assertThat(token).as("available scan lease").isNotNull(); return token;
    }
    private int batch(Object scan,long token) throws Exception { return ((Number)invoke(scan,"processBatch",token)).intValue(); }
    private long ownedAlerts() { return count("SELECT COUNT(*) FROM ai_care_alert WHERE elder_id=?",ELDER); }
    private String key(long plan) { return yesterday+"-med-"+plan+"-早"; }
    private boolean tableExists(String name) {
        return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",name)>0;
    }
    private long number(String sql,Object... parameters) { return jdbc.queryForObject(sql,Long.class,parameters); }
    private long count(String sql,Object... parameters) { return number(sql,parameters); }
    private String login(long id) {
        JsonNode result=post("/account/login",null,map("phone","19900"+id,"pass",PASSWORD)); ok(result);
        String token=result.path("data").path("token").asText(); assertThat(token).isNotBlank(); return token;
    }
    private JsonNode firstNotice(String token) {
        JsonNode result=get("/ai/daily/notifications?afterId=0&limit=20",token); ok(result);
        assertThat(result.path("data").path("items").size()).isEqualTo(1);
        return result.path("data").path("items").get(0);
    }
    private long unread(String token) {
        JsonNode result=get("/ai/daily/notification-count",token); ok(result);
        return result.path("data").path("unreadCount").asLong(-1);
    }
    private JsonNode get(String path,String token) { return request(HttpMethod.GET,path,token,null); }
    private JsonNode post(String path,String token,Object body) { return request(HttpMethod.POST,path,token,body); }
    private JsonNode request(HttpMethod method,String path,String token,Object body) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.set("token",token);
        JsonNode result=http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class).getBody();
        assertThat(result).as(method+" "+path).isNotNull(); return result;
    }
    private void ok(JsonNode result) { assertThat(result.path("code").asInt()).as(result.toString()).isEqualTo(200); }
    private static Map<String,Object> map(Object... entries) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<entries.length;i+=2) result.put((String)entries[i],entries[i+1]);
        return result;
    }
}
