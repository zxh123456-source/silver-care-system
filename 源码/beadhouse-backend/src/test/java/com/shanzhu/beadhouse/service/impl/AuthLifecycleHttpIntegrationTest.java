package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SCOPE_HTTP_REDIS_PORT", matches="[0-9]+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}",
    "spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
    "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}", "spring.redis.password=scope-redis-only",
    "ai.rag.enabled=false", "ai.provider.enabled=false", "security.password-reset.email-enabled=false",
    "filesave.windows=./target/scope-exports", "spring.quartz.auto-startup=false"})
class AuthLifecycleHttpIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void allowJdkClientToRead401ResponseBodies() {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        http.getRestTemplate().setRequestFactory(factory);
    }
    @Test void databaseRoleChangeCannotKeepGrantingCachedPrivileges() {
        String pass="auth-role-refresh-test-only";
        jdbc.update("UPDATE staff SET pass=? WHERE id=4",new BCryptPasswordEncoder().encode(pass));
        Long role=jdbc.queryForObject("SELECT role_id FROM staff WHERE id=4",Long.class);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) SELECT ?,50,1,NOW(),1,NOW() WHERE NOT EXISTS(SELECT 1 FROM role_auth WHERE role_id=? AND auth_id=50)",role,role);
        String token=login(4,pass,null).path("token").asText();
        try {
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks",token,null),200);
            // Simulate a committed role change whose cache invalidation did not reach Redis.
            jdbc.update("UPDATE staff SET role_id=? WHERE id=4",role.equals(7L)?5:7);
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks",token,null),401);
        } finally { jdbc.update("UPDATE staff SET role_id=? WHERE id=4",role); }
    }
    @Test void realSessionRotationLogoutPermissionsAndStaffChangesUseConsistentStatusCodes() {
        String pass="auth-lifecycle-test-only";
        jdbc.update("UPDATE staff SET pass=? WHERE id IN(1,4)",new BCryptPasswordEncoder().encode(pass));
        Long role=jdbc.queryForObject("SELECT role_id FROM staff WHERE id=4",Long.class);
        Long priorScopePermission=jdbc.queryForObject("SELECT COUNT(*) FROM role_auth WHERE role_id=? AND auth_id=49",Long.class,role);
        jdbc.update("DELETE FROM role_auth WHERE role_id=? AND auth_id=49",role);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) SELECT ?,a.id,1,NOW(),1,NOW() FROM auth a WHERE a.id=50 AND NOT EXISTS(SELECT 1 FROM role_auth r WHERE r.role_id=? AND r.auth_id=a.id)",role,role);
        try {
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks",null,null),401);
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks","malformed",null),401);
            String account=jdbc.queryForObject("SELECT phone FROM staff WHERE id=4",String.class);
            ResponseEntity<JsonNode> wrong=call(HttpMethod.POST,"/account/login","stale-token",map("phone",account,"pass","incorrect"));
            ResponseEntity<JsonNode> absent=call(HttpMethod.POST,"/account/login",null,map("phone","00000000000","pass","incorrect"));
            assertCode(wrong,401); assertCode(absent,401);
            assertThat(wrong.getBody()).isEqualTo(absent.getBody());
            JsonNode first=login(4,pass,"expired-token");
            assertThat(first.has("pass")).isFalse(); assertThat(first.has("phone")).isFalse();
            String old=first.path("token").asText(), current=login(4,pass,old).path("token").asText();
            assertThat(old).isNotEqualTo(current);
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks",old,null),401);
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks",current,null),200);
            assertCode(call(HttpMethod.GET,"/ai/scope/assignments",current,null),403);
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks",current,null),200); // Forbidden does not revoke the session.
            assertCode(call(HttpMethod.DELETE,"/account/logout",current,null),200);
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks",current,null),401);
            String renewed=login(4,pass,null).path("token").asText();
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks",current,null),401); // Logged-out token never revives.
            String admin=login(1,pass,null).path("token").asText();
            assertCode(call(HttpMethod.PUT,"/staff/editStaff",admin,map("id",4,"roleId",role.equals(7L)?5:7)),200);
            assertCode(call(HttpMethod.GET,"/ai/daily/tasks",renewed,null),401);
        } finally {
            jdbc.update("UPDATE staff SET role_id=? WHERE id=4",role);
            if(priorScopePermission>0) jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) VALUES(?,49,1,NOW(),1,NOW())",role);
        }
    }
    private JsonNode login(int id,String password,String token) {
        String account=jdbc.queryForObject("SELECT phone FROM staff WHERE id=?",String.class,id);
        ResponseEntity<JsonNode> response=call(HttpMethod.POST,"/account/login",token,map("phone",account,"pass",password));
        assertCode(response,200); return response.getBody().path("data");
    }
    private void assertCode(ResponseEntity<JsonNode> response,int code) {
        assertThat(response.getStatusCodeValue()).isEqualTo(code);
        assertThat(response.getBody().path("code").asInt()).isEqualTo(code);
    }
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,String token,Object body) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.set("token",token);
        return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    private Map<String,Object> map(Object... values) {
        Map<String,Object> result=new HashMap<>(); for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]); return result;
    }
}
