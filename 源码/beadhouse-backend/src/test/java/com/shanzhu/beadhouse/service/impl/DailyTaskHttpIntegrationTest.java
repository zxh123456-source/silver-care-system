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
