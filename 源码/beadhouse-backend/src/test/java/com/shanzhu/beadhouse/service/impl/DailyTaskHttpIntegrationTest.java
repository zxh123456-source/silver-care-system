package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DAILY_HTTP_TEST",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}","spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
    "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}","spring.redis.password=scope-redis-only",
    "ai.rag.enabled=false","ai.provider.enabled=false","security.password-reset.email-enabled=false",
    "spring.quartz.auto-startup=false"})
class DailyTaskHttpIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @Test void moreThan200SourcesSyncCompletelyAndRetryPreservesOwnershipAndCompletion() {
        String password="overflow-test-login-only";
        jdbc.update("UPDATE staff SET pass=? WHERE id=1",new BCryptPasswordEncoder().encode(password));
        String admin=login(1,password);
        String marker="overflow-"+UUID.randomUUID();
        List<Object[]> sources=new ArrayList<>();
        for(int i=0;i<201;i++) sources.add(new Object[]{marker});
        jdbc.batchUpdate("INSERT INTO care_note(elder_id,staff_id,event_time,source_text,follow_up,status,ai_generated) VALUES(4,1,DATE_SUB(NOW(),INTERVAL 1 DAY),?,'请人工复核','PENDING','N')",sources);
        try {
            JsonNode overview=call(HttpMethod.GET,"/ai/daily/overview",admin,null); ok(overview);
            assertThat(overview.path("data").path("items").size()).isEqualTo(200);
            assertThat(overview.path("data").path("truncated").asBoolean()).isTrue();
            JsonNode response=call(HttpMethod.POST,"/ai/daily/tasks/sync",admin,null);
            ok(response);
            String fixtureTasks="FROM ai_daily_task WHERE category='CARE_FOLLOW_UP' AND source_id IN (SELECT id FROM care_note WHERE source_text=?)";
            assertThat(jdbc.queryForObject("SELECT COUNT(*) "+fixtureTasks,Long.class,marker)).isEqualTo(201);
            List<Long> ids=jdbc.queryForList("SELECT id "+fixtureTasks+" ORDER BY id",Long.class,marker);
            jdbc.update("UPDATE ai_daily_task SET state='CLAIMED',owner_id=1,revision=7 WHERE id=?",ids.get(100));
            jdbc.update("UPDATE ai_daily_task SET state='DONE',owner_id=1,revision=9,completion_note='已复核' WHERE id=?",ids.get(200));
            ok(call(HttpMethod.POST,"/ai/daily/tasks/sync",admin,null));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) "+fixtureTasks,Long.class,marker)).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT state FROM ai_daily_task WHERE id=?",String.class,ids.get(100))).isEqualTo("CLAIMED");
            assertThat(jdbc.queryForObject("SELECT owner_id FROM ai_daily_task WHERE id=?",Long.class,ids.get(100))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM ai_daily_task WHERE id=?",String.class,ids.get(200))).isEqualTo("DONE");
            assertThat(jdbc.queryForObject("SELECT revision FROM ai_daily_task WHERE id=?",Integer.class,ids.get(200))).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT completion_note FROM ai_daily_task WHERE id=?",String.class,ids.get(200))).isEqualTo("已复核");
        } finally {
            jdbc.update("DELETE FROM ai_daily_task WHERE category='CARE_FOLLOW_UP' AND source_id IN (SELECT id FROM care_note WHERE source_text=?)",marker);
            jdbc.update("DELETE FROM care_note WHERE source_text=?",marker);
        }
    }

    @Test void bulkMedicationAndReservationsRespectScopeAndDoNotExecuteBusiness() {
        String password="bulk-scope-test-only";
        jdbc.update("UPDATE staff SET pass=? WHERE id=4",new BCryptPasswordEncoder().encode(password));
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) SELECT 5,a.id,1,NOW(),1,NOW() FROM auth a WHERE NOT EXISTS(SELECT 1 FROM role_auth r WHERE r.role_id=5 AND r.auth_id=a.id)");
        jdbc.update("DELETE FROM elder_staff_assignment WHERE staff_id=4");
        jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active) VALUES(4,4,'Y')");
        String token=login(4,password);
        String marker="bulk-"+UUID.randomUUID(), reserveMarker="b"+UUID.randomUUID().toString().substring(0,8);
        List<Object[]> rows=new ArrayList<>(); for(int i=0;i<101;i++) rows.add(new Object[]{marker});
        jdbc.batchUpdate("INSERT INTO medication_plan(elder_id,medicine_name,dose_instruction,periods,start_date) VALUES(4,?,'按已核对医嘱','早,晚',CURDATE())",rows);
        rows=new ArrayList<>(); for(int i=0;i<101;i++) rows.add(new Object[]{reserveMarker});
        jdbc.batchUpdate("INSERT INTO nurse_reserve(elder_id,service_name,need_date,service_price,charge_method,frequency,pay_amount,order_flag,create_id,create_time,update_id,update_time) VALUES(4,?,1,1,'按次',1,1,'N',1,NOW(),1,NOW())",rows);
        jdbc.update("INSERT INTO care_note(elder_id,event_time,source_text,follow_up,status) VALUES(8,NOW(),?,'越权来源','PENDING')",marker);
        Long firstPlan=jdbc.queryForObject("SELECT MIN(id) FROM medication_plan WHERE medicine_name=?",Long.class,marker);
        Long lastPlan=jdbc.queryForObject("SELECT MAX(id) FROM medication_plan WHERE medicine_name=?",Long.class,marker);
        jdbc.update("INSERT INTO medication_execution(plan_id,elder_id,execution_date,period,status,executed_time) VALUES(?,4,CURDATE(),'早','DONE',NOW())",firstPlan);
        jdbc.update("INSERT INTO medication_execution(plan_id,elder_id,execution_date,period,status,executed_time,note) VALUES(?,4,CURDATE(),'晚','SKIPPED',NOW(),'测试未执行原因')",lastPlan);
        String measuredAt=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        JsonNode firstMeasurement=call(HttpMethod.POST,"/ai/health/measurement",token,map("elderId",4,"temperature",36.0,"measureTime",measuredAt)); ok(firstMeasurement);
        JsonNode lastMeasurement=call(HttpMethod.POST,"/ai/health/measurement",token,map("elderId",4,"temperature",38.2,"measureTime",measuredAt)); ok(lastMeasurement);
        long firstMeasurementId=firstMeasurement.path("data").path("id").asLong(), lastMeasurementId=lastMeasurement.path("data").path("id").asLong();
        // Remove the auto-created alert task to prove source traversal really recreates this health snapshot.
        jdbc.update("DELETE FROM ai_daily_task WHERE task_key=?","health-"+lastMeasurementId);
        try {
            String before=fingerprint();
            String healthBefore=jdbc.queryForList("SELECT id,temperature,measure_time FROM health_data ORDER BY id").toString();
            JsonNode overview=call(HttpMethod.GET,"/ai/daily/overview",token,null); ok(overview);
            assertThat(overview.path("data").path("truncated").asBoolean()).isTrue();
            JsonNode synced=call(HttpMethod.POST,"/ai/daily/tasks/sync",token,null); ok(synced);
            assertThat(synced.path("data").asInt()).isEqualTo(overview.path("data").path("attentionCount").asInt());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_daily_task WHERE category LIKE 'MEDICATION_%' AND source_id IN(SELECT id FROM medication_plan WHERE medicine_name=?)",Long.class,marker)).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_daily_task WHERE category='SERVICE_BACKLOG' AND source_id IN(SELECT id FROM nurse_reserve WHERE service_name=?)",Long.class,reserveMarker)).isEqualTo(101);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_daily_task WHERE category='CARE_FOLLOW_UP' AND source_id IN(SELECT id FROM care_note WHERE source_text=?)",Long.class,marker)).isZero();
            assertThat(jdbc.queryForObject("SELECT detail FROM ai_daily_task WHERE category='MEDICATION_SKIPPED' AND source_id=?",String.class,lastPlan)).contains("测试未执行原因");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_daily_task WHERE task_key=? AND category='HEALTH_CHANGE'",Long.class,"health-"+lastMeasurementId)).isEqualTo(1);
            assertThat(fingerprint()).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT id,temperature,measure_time FROM health_data ORDER BY id").toString()).isEqualTo(healthBefore);
        } finally {
            jdbc.update("DELETE FROM ai_daily_task WHERE category='HEALTH_CHANGE' AND source_id IN (?,?)",firstMeasurementId,lastMeasurementId);
            jdbc.update("DELETE FROM ai_care_alert WHERE kind='HEALTH_CHANGE' AND source_id IN (?,?)",firstMeasurementId,lastMeasurementId);
            jdbc.update("DELETE FROM health_data WHERE id IN (?,?)",firstMeasurementId,lastMeasurementId);
            jdbc.update("DELETE FROM ai_daily_task WHERE category LIKE 'MEDICATION_%' AND source_id IN(SELECT id FROM medication_plan WHERE medicine_name=?)",marker);
            jdbc.update("DELETE FROM ai_daily_task WHERE category='SERVICE_BACKLOG' AND source_id IN(SELECT id FROM nurse_reserve WHERE service_name=?)",reserveMarker);
            jdbc.update("DELETE FROM medication_execution WHERE plan_id IN(SELECT id FROM medication_plan WHERE medicine_name=?)",marker);
            jdbc.update("DELETE FROM medication_plan WHERE medicine_name=?",marker);
            jdbc.update("DELETE FROM nurse_reserve WHERE service_name=?",reserveMarker);
            jdbc.update("DELETE FROM care_note WHERE source_text=?",marker);
        }
    }

    @Test void laterBatchFailureRollsBackEarlierBatchesAndAllowsRetry() {
        String password="bulk-rollback-test-only";
        jdbc.update("UPDATE staff SET pass=? WHERE id=1",new BCryptPasswordEncoder().encode(password));
        String admin=login(1,password), marker="rollback-"+UUID.randomUUID();
        List<Object[]> rows=new ArrayList<>(); for(int i=0;i<201;i++) rows.add(new Object[]{marker});
        jdbc.batchUpdate("INSERT INTO care_note(elder_id,event_time,source_text,follow_up,status) VALUES(4,DATE_SUB(NOW(),INTERVAL 1 DAY),?,'请复核','PENDING')",rows);
        Long failureId=jdbc.queryForObject("SELECT id FROM care_note WHERE source_text=? ORDER BY id LIMIT 1 OFFSET 120",Long.class,marker);
        String trigger="bulk_fail_"+UUID.randomUUID().toString().replace("-", "");
        try {
            String before=jdbc.queryForList("SELECT task_key,state,owner_id,revision FROM ai_daily_task ORDER BY id").toString();
            jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON ai_daily_task FOR EACH ROW BEGIN IF NEW.task_key='care-"+failureId+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bulk-fixture-failure'; END IF; END");
            try {
                assertThat(call(HttpMethod.POST,"/ai/daily/tasks/sync",admin,null).path("code").asInt()).isEqualTo(500);
                assertThat(jdbc.queryForList("SELECT task_key,state,owner_id,revision FROM ai_daily_task ORDER BY id").toString()).isEqualTo(before);
            } finally { jdbc.execute("DROP TRIGGER "+trigger); }
            ok(call(HttpMethod.POST,"/ai/daily/tasks/sync",admin,null));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_daily_task WHERE category='CARE_FOLLOW_UP' AND source_id IN(SELECT id FROM care_note WHERE source_text=?)",Long.class,marker)).isEqualTo(201);
        } finally {
            jdbc.update("DELETE FROM ai_daily_task WHERE category='CARE_FOLLOW_UP' AND source_id IN(SELECT id FROM care_note WHERE source_text=?)",marker);
            jdbc.update("DELETE FROM care_note WHERE source_text=?",marker);
        }
    }

    @Test void realTaskLifecycleIsConcurrentSafeScopedAndDoesNotExecuteBusiness() throws Exception {
        String password="daily-test-login-only";
        jdbc.update("UPDATE staff SET pass=? WHERE id IN(1,4,6,7)",new BCryptPasswordEncoder().encode(password));
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) SELECT s.role_id,a.id,1,NOW(),1,NOW() FROM staff s CROSS JOIN auth a WHERE s.id IN(4,6,7) AND NOT EXISTS(SELECT 1 FROM role_auth r WHERE r.role_id=s.role_id AND r.auth_id=a.id)");
        jdbc.update("DELETE FROM elder_staff_assignment");
        jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active) VALUES(4,4,'Y'),(4,6,'Y')");
        jdbc.update("INSERT INTO care_note(elder_id,staff_id,event_time,source_text,follow_up,status,ai_generated) VALUES(4,4,DATE_SUB(NOW(),INTERVAL 2 DAY),'daily-lifecycle-fixture','请人工复核','PENDING','N')");
        Long noteId=jdbc.queryForObject("SELECT MAX(id) FROM care_note WHERE source_text='daily-lifecycle-fixture'",Long.class);
        String token4=login(4,password),token6=login(6,password), outsider=login(7,password),admin=login(1,password);
        String before=fingerprint();
        // A GET must not materialize tasks.
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM ai_daily_task",Long.class);
        call(HttpMethod.GET,"/ai/daily/overview",token4,null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_daily_task",Long.class)).isEqualTo(count);
        ok(call(HttpMethod.POST,"/ai/daily/tasks/sync",token4,null));
        ok(call(HttpMethod.POST,"/ai/daily/tasks/sync",token4,null));
        String key="care-"+noteId;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_daily_task WHERE task_key=?",Long.class,key)).isEqualTo(1);
        JsonNode task=find(token4,key);
        assertThat(task.path("overdue").asBoolean()).isTrue();
        assertThat(call(HttpMethod.GET,"/ai/daily/tasks?scopeStaffId=1",outsider,null).path("data").size()).isZero();
        Map<String,Object> action=map("id",task.path("id").asLong(),"revision",task.path("revision").asInt(),"targetStaffId",1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        List<JsonNode> responses=new ArrayList<>();
        final Map<String,Object> claimAction=action;
        try {
            List<Callable<JsonNode>> requests=Arrays.asList(
                () -> call(HttpMethod.POST,"/ai/daily/tasks/claim",token4,claimAction),
                () -> call(HttpMethod.POST,"/ai/daily/tasks/claim",token6,claimAction));
            for(Future<JsonNode> result:pool.invokeAll(requests)) responses.add(result.get());
        } finally { pool.shutdownNow(); }
        assertThat(responses.stream().filter(r -> r.path("code").asInt()==200).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.path("code").asInt()==409).count()).isEqualTo(1);
        JsonNode claimed=find(token4,key);
        long owner=claimed.path("ownerId").asLong(), recipient=owner==4?6:4;
        String ownerToken=owner==4?token4:token6, nextToken=owner==4?token6:token4;
        action=map("id",claimed.path("id").asLong(),"revision",claimed.path("revision").asInt(),"targetStaffId",7);
        assertThat(call(HttpMethod.POST,"/ai/daily/tasks/transfer",ownerToken,action).path("code").asInt()).isEqualTo(400);
        action.put("targetStaffId",recipient); action.put("revision",-1);
        assertThat(call(HttpMethod.POST,"/ai/daily/tasks/transfer",ownerToken,action).path("code").asInt()).isEqualTo(409);
        action.put("revision",claimed.path("revision").asInt());
        ok(call(HttpMethod.POST,"/ai/daily/tasks/transfer",ownerToken,action));
        JsonNode transferred=find(nextToken,key);
        action=map("id",transferred.path("id").asLong(),"revision",transferred.path("revision").asInt(),"note","已人工复核，原业务另行确认");
        assertThat(call(HttpMethod.POST,"/ai/daily/tasks/complete",ownerToken,action).path("code").asInt()).isEqualTo(403);
        ok(call(HttpMethod.POST,"/ai/daily/tasks/complete",nextToken,action));
        int revision=find(nextToken,key).path("revision").asInt();
        ok(call(HttpMethod.POST,"/ai/daily/tasks/complete",nextToken,action));
        ok(call(HttpMethod.POST,"/ai/daily/tasks/sync",nextToken,null));
        JsonNode completed=find(nextToken,key);
        assertThat(completed.path("state").asText()).isEqualTo("DONE");
        assertThat(completed.path("revision").asInt()).isEqualTo(revision);
        assertThat(completed.path("overdue").asBoolean()).isFalse();
        assertThat(fingerprint()).isEqualTo(before);
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE staff_id=?",recipient);
        assertThat(call(HttpMethod.POST,"/ai/daily/tasks/complete",nextToken,action).path("code").asInt()).isEqualTo(403);
        assertThat(find(admin,key).path("state").asText()).isEqualTo("DONE");
    }
    private JsonNode find(String token,String key) {
        JsonNode list=call(HttpMethod.GET,"/ai/daily/tasks?state=ALL",token,null); ok(list);
        for(JsonNode task:list.path("data")) if(key.equals(task.path("taskKey").asText())) return task;
        throw new AssertionError("task missing");
    }
    private String login(int id,String password) {
        String phone=jdbc.queryForObject("SELECT phone FROM staff WHERE id=?",String.class,id);
        JsonNode result=call(HttpMethod.POST,"/account/login",null,map("phone",phone,"pass",password)); ok(result);
        return result.path("data").path("token").asText();
    }
    private void ok(JsonNode node) { assertThat(node.path("code").asInt()).isEqualTo(200); }
    private JsonNode call(HttpMethod method,String path,String token,Object body) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.set("token",token);
        return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class).getBody();
    }
    private Map<String,Object> map(Object... pairs) {
        Map<String,Object> data=new HashMap<>(); for(int i=0;i<pairs.length;i+=2) data.put((String)pairs[i],pairs[i+1]); return data;
    }
    private String fingerprint() {
        return jdbc.queryForList("SELECT id,balance FROM elder").toString()
            +jdbc.queryForList("SELECT id,status FROM care_note").toString()
            +jdbc.queryForList("SELECT id,order_flag FROM nurse_reserve").toString()
            +jdbc.queryForObject("SELECT COUNT(*) FROM medication_execution",Long.class)
            +jdbc.queryForObject("SELECT COUNT(*) FROM consume",Long.class);
    }
}
