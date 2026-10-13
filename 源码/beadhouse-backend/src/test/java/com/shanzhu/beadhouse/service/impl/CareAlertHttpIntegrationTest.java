package com.shanzhu.beadhouse.service.impl;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;
import java.text.SimpleDateFormat;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ALERT_HTTP_TEST",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}","spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
    "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}","spring.redis.password=scope-redis-only",
    "ai.rag.enabled=false","ai.provider.enabled=false","security.password-reset.email-enabled=false","spring.quartz.auto-startup=false"})
class CareAlertHttpIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Test void alertsPersistDeduplicateResolveAndRespectScopeWithoutProxyMedicalWrites() {
        String password="alert-test-login-only";
        jdbc.update("UPDATE staff SET pass=? WHERE id IN(4,6,7)",new BCryptPasswordEncoder().encode(password));
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) SELECT s.role_id,a.id,1,NOW(),1,NOW() FROM staff s CROSS JOIN auth a WHERE s.id IN(4,6,7) AND NOT EXISTS(SELECT 1 FROM role_auth r WHERE r.role_id=s.role_id AND r.auth_id=a.id)");
        jdbc.update("DELETE FROM elder_staff_assignment");
        jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active) VALUES(4,4,'Y'),(4,6,'Y')");
        jdbc.update("UPDATE elder SET check_flag='入住' WHERE id=4");
        jdbc.update("DELETE FROM ai_care_alert"); jdbc.update("DELETE FROM health_data WHERE elder_id=4");
        String staff=login(4,password), other=login(6,password), outsider=login(7,password);
        String today=new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String yesterday=new SimpleDateFormat("yyyy-MM-dd").format(new Date(System.currentTimeMillis()-86400000L));
        ok(call("/ai/health/measurement",staff,map("elderId",4,"temperature",36.0,"measureTime",yesterday+" 08:00:00")));
        JsonNode measured=call("/ai/health/measurement",staff,map("elderId",4,"temperature",38.2,"measureTime",yesterday+" 09:00:00")); ok(measured);
        String healthKey="health-"+measured.path("data").path("id").asLong();
        JsonNode health=find(staff,healthKey);
        assertThat(health.path("kind").asText()).isEqualTo("HEALTH_CHANGE");
        ok(call("/ai/health/measurement",staff,map("elderId",4,"weight",65.0,"measureTime",yesterday+" 10:00:00")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_care_alert WHERE kind='HEALTH_CHANGE'",Long.class)).isEqualTo(1);
        String sourceBefore=jdbc.queryForList("SELECT id,temperature,weight FROM health_data").toString();
        Map<String,Object> action=map("id",health.path("id").asLong(),"revision",health.path("revision").asInt(),"targetStaffId",6);
        ok(call("/ai/daily/alerts/ack",staff,action));
        health=find(staff,healthKey);
        action=map("id",health.path("id").asLong(),"revision",health.path("revision").asInt(),"note","已由工作人员核对测量记录");
        assertThat(call("/ai/daily/alerts/resolve",other,action).path("code").asInt()).isEqualTo(403);
        ok(call("/ai/daily/alerts/resolve",staff,action)); ok(call("/ai/daily/alerts/resolve",staff,action));
        assertThat(jdbc.queryForList("SELECT id,temperature,weight FROM health_data").toString()).isEqualTo(sourceBefore);
        assertThat(get("/ai/daily/alerts?state=ALL",outsider).path("data").size()).isZero();
        assertThat(call("/ai/daily/alerts/ack",outsider,action).path("code").asInt()).isEqualTo(403);

        jdbc.update("INSERT INTO medication_plan(elder_id,medicine_name,dose_instruction,periods,start_date,enabled) VALUES(4,'alert-test','按已核对医嘱','早',?,'Y')",yesterday);
        Long plan=jdbc.queryForObject("SELECT MAX(id) FROM medication_plan",Long.class);
        assertThat(call("/ai/daily/alerts/scan?date="+today,staff,null).path("code").asInt()).isEqualTo(400);
        ok(call("/ai/daily/alerts/scan?date="+yesterday,staff,null)); ok(call("/ai/daily/alerts/scan?date="+yesterday,staff,null));
        String medicationKey=yesterday+"-med-"+plan+"-早";
        JsonNode missing=find(staff,medicationKey);
        assertThat(missing.path("kind").asText()).isEqualTo("MEDICATION_UNRECORDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_care_alert WHERE alert_key=?",Long.class,medicationKey)).isEqualTo(1);
        jdbc.update("UPDATE ai_daily_task SET state='DONE' WHERE task_key=?",medicationKey);
        Map<String,Object> execution=map("planId",plan,"executionDate",yesterday,"period","早","status","SKIPPED","note","已登记未执行原因");
        ok(call("/ai/medication/execute",staff,execution));
        JsonNode skipped=find(staff,medicationKey);
        assertThat(skipped.path("kind").asText()).isEqualTo("MEDICATION_SKIPPED");
        assertThat(skipped.path("state").asText()).isEqualTo("OPEN");
        assertThat(skipped.path("taskKey").asText()).isNotEqualTo(medicationKey);
        assertThat(jdbc.queryForObject("SELECT state FROM ai_daily_task WHERE task_key=?",String.class,medicationKey)).isEqualTo("DONE");
        ok(call("/ai/daily/alerts/ack",staff,map("id",skipped.path("id").asLong(),"revision",skipped.path("revision").asInt())));
        ok(call("/ai/medication/execute",staff,execution));
        assertThat(find(staff,medicationKey).path("state").asText()).isEqualTo("ACKNOWLEDGED");
        execution.put("status","DONE"); ok(call("/ai/medication/execute",staff,execution));
        assertThat(find(staff,medicationKey).path("state").asText()).isEqualTo("RESOLVED");
        ok(call("/ai/daily/alerts/scan?date="+yesterday,staff,null));
        assertThat(find(staff,medicationKey).path("state").asText()).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM medication_execution WHERE plan_id=?",Long.class,plan)).isEqualTo(1);
        jdbc.update("INSERT INTO medication_plan(elder_id,medicine_name,dose_instruction,periods,start_date,enabled) VALUES(4,'race-test','按已核对医嘱','早',?,'Y')",yesterday);
        Long racePlan=jdbc.queryForObject("SELECT MAX(id) FROM medication_plan",Long.class);
        Map<String,Object> raceExecution=map("planId",racePlan,"executionDate",yesterday,"period","早","status","DONE");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Callable<JsonNode>> requests=Arrays.asList(
                () -> call("/ai/daily/alerts/scan?date="+yesterday,staff,null),
                () -> call("/ai/medication/execute",staff,raceExecution));
            for(Future<JsonNode> response:pool.invokeAll(requests)) ok(response.get());
        } catch(Exception e) { throw new AssertionError("concurrent scan/registration failed",e); }
        finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_care_alert WHERE alert_key=? AND state!='RESOLVED'",Long.class,
            yesterday+"-med-"+racePlan+"-早")).isZero();
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE staff_id=4");
        assertThat(call("/ai/daily/alerts/resolve",staff,action).path("code").asInt()).isEqualTo(403);
        jdbc.update("UPDATE elder_staff_assignment SET active='Y' WHERE staff_id=4");
        // Source and alert must commit together; simulate persistence failure only in this disposable DB.
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM health_data",Long.class);
        jdbc.execute("RENAME TABLE ai_care_alert TO ai_care_alert_test_hold");
        try {
            assertThat(call("/ai/health/measurement",staff,map("elderId",4,"temperature",40.0,"measureTime",yesterday+" 11:00:00")).path("code").asInt()).isNotEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM health_data",Long.class)).isEqualTo(before);
        } finally { jdbc.execute("RENAME TABLE ai_care_alert_test_hold TO ai_care_alert"); }
    }
    private String login(int id,String password) {
        String phone=jdbc.queryForObject("SELECT phone FROM staff WHERE id=?",String.class,id);
        JsonNode result=call("/account/login",null,map("phone",phone,"pass",password)); ok(result); return result.path("data").path("token").asText();
    }
    private JsonNode find(String token,String key) { JsonNode data=get("/ai/daily/alerts?state=ALL",token); ok(data); for(JsonNode row:data.path("data"))if(key.equals(row.path("alertKey").asText()))return row; throw new AssertionError("alert missing"); }
    private void ok(JsonNode data) { assertThat(data.path("code").asInt()).isEqualTo(200); }
    private JsonNode get(String path,String token) { return request(HttpMethod.GET,path,token,null); }
    private JsonNode call(String path,String token,Object data) { return request(HttpMethod.POST,path,token,data); }
    private JsonNode request(HttpMethod method,String path,String token,Object data) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null)headers.set("token",token);
        return http.exchange(path,method,new HttpEntity<>(data,headers),JsonNode.class).getBody();
    }
    private Map<String,Object> map(Object... values) { Map<String,Object> data=new HashMap<>();for(int i=0;i<values.length;i+=2)data.put((String)values[i],values[i+1]);return data; }
}
