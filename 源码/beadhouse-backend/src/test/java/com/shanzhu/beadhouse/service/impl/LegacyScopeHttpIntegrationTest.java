package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SCOPE_HTTP_REDIS_PORT", matches="[0-9]+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}",
    "spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
    "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}", "spring.redis.password=scope-redis-only",
    "ai.rag.enabled=false", "ai.provider.enabled=false", "security.password-reset.email-enabled=false",
    "filesave.windows=./target/scope-exports", "spring.quartz.auto-startup=false"})
class LegacyScopeHttpIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test void realSessionsEnforceListsExportWritesAndImmediateRevocation() throws Exception {
        String password="scope-test-login-only";
        String hash=new BCryptPasswordEncoder().encode(password);
        jdbc.update("UPDATE staff SET pass=? WHERE id IN (1,4)",hash);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) SELECT 5,a.id,1,NOW(),1,NOW() FROM auth a WHERE NOT EXISTS(SELECT 1 FROM role_auth r WHERE r.role_id=5 AND r.auth_id=a.id)");
        jdbc.update("UPDATE elder SET name='allowed',check_flag='入住' WHERE id=4");
        jdbc.update("UPDATE elder SET name='foreign',check_flag='入住' WHERE id=8");
        jdbc.update("DELETE FROM elder_staff_assignment");
        jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active) VALUES(4,4,'Y')");
        Long reservation=jdbc.queryForObject("SELECT MIN(id) FROM nurse_reserve",Long.class);
        Long accident=jdbc.queryForObject("SELECT MIN(id) FROM accident",Long.class);
        Long outward=jdbc.queryForObject("SELECT MIN(id) FROM outward",Long.class);
        Long order=jdbc.queryForObject("SELECT MIN(id) FROM `order`",Long.class);
        jdbc.update("UPDATE nurse_reserve SET elder_id=8,staff_id=NULL,nurse_date=NULL,order_flag='N' WHERE id=?",reservation);
        jdbc.update("UPDATE accident SET elder_id=8,del_flag='N' WHERE id=?",accident);
        jdbc.update("UPDATE outward SET elder_id=8,del_flag='N',real_return_date=NULL WHERE id=?",outward);
        jdbc.update("UPDATE `order` SET elder_id=8,staff_id=NULL,deliver_dishes_date=NULL,order_flag='N' WHERE id=?",order);
        String admin=login(1,password), staff=login(4,password);
        assertThat(call(HttpMethod.GET,"/elderRecord/getElderRecordById?elderId=4",staff,null).path("code").asInt()).isEqualTo(200);
        assertThat(call(HttpMethod.GET,"/elderRecord/getElderRecordById?elderId=8",staff,null).path("code").asInt()).isEqualTo(403);
        JsonNode list=call(HttpMethod.GET,"/elderRecord/pageElderByKey?pageNum=1&pageSize=100&scopeStaffId=1",staff,null);
        assertThat(list.path("data").path("total").asInt()).isEqualTo(1);
        assertThat(list.path("data").path("list").get(0).path("id").asLong()).isEqualTo(4);
        for(String path:Arrays.asList("/accident/pageAccidentByKey","/outward/pageOutwardByKey",
                "/nurseReserve/pageNurseReserveByKey","/order/pageOrderByKey","/consume/pageConsumeByKey",
                "/depositRecharge/pageDepositRechargeByKey")) {
            JsonNode page=call(HttpMethod.GET,path+"?pageNum=1&pageSize=100",staff,null);
            assertThat(page.path("code").asInt()).as(path).isEqualTo(200);
            for(JsonNode row:page.path("data").path("list")) assertThat(row.path("elderName").asText()).isEqualTo("allowed");
        }
        Files.createDirectories(Paths.get("target/scope-exports/download"));
        JsonNode export=call(HttpMethod.GET,"/elderRecord/exportExcel",staff,null);
        assertThat(export.path("code").asInt()).isEqualTo(200);
        String url=export.path("data").asText();
        Path file=Paths.get("target/scope-exports/download",url.substring(url.lastIndexOf('/')+1));
        try(InputStream input=Files.newInputStream(file); Workbook book=WorkbookFactory.create(input)) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);
        }
        String before=fingerprint();
        denied(HttpMethod.PUT,"/depositRecharge/recharge",staff,map("elderId",8,"amount",1));
        denied(HttpMethod.POST,"/nurseReserve/addNurseReserve",staff,map("elderId",8));
        denied(HttpMethod.PUT,"/nurseReserve/executeNurseReserve",staff,map("id",reservation));
        denied(HttpMethod.PUT,"/order/sendOrder",staff,map("id",order));
        denied(HttpMethod.POST,"/accident/addAccident",staff,map("elderId",8));
        denied(HttpMethod.DELETE,"/accident/deleteAccident?accidentId="+accident,staff,null);
        denied(HttpMethod.PUT,"/outward/recordReturn",staff,map("id",outward));
        denied(HttpMethod.PUT,"/elderRecord/editElder",staff,map("id",8));
        denied(HttpMethod.DELETE,"/elderRecord/deleteElder?elderId=8",staff,null);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(call(HttpMethod.GET,"/elderRecord/getElderRecordById?elderId=8",admin,null).path("code").asInt()).isEqualTo(200);
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE staff_id=4");
        denied(HttpMethod.GET,"/elderRecord/getElderRecordById?elderId=4",staff,null);
        assertThat(call(HttpMethod.GET,"/elderRecord/pageElderByKey?pageNum=1&pageSize=100",staff,null)
                .path("data").path("total").asInt()).isZero();
    }
    private String login(int id,String password) {
        String phone=jdbc.queryForObject("SELECT phone FROM staff WHERE id=?",String.class,id);
        JsonNode result=call(HttpMethod.POST,"/account/login",null,map("phone",phone,"pass",password));
        assertThat(result.path("code").asInt()).isEqualTo(200); return result.path("data").path("token").asText();
    }
    private void denied(HttpMethod method,String path,String token,Object body) {
        assertThat(call(method,path,token,body).path("code").asInt()).as(path).isEqualTo(403);
    }
    private JsonNode call(HttpMethod method,String path,String token,Object body) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.set("token",token);
        return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class).getBody();
    }
    private Map<String,Object> map(Object... entries) {
        Map<String,Object> map=new HashMap<>();
        for(int i=0;i<entries.length;i+=2) map.put((String)entries[i],entries[i+1]); return map;
    }
    private String fingerprint() {
        return jdbc.queryForList("SELECT id,balance,check_flag FROM elder WHERE id IN(4,8)").toString()
            +jdbc.queryForList("SELECT id,order_flag,staff_id,nurse_date FROM nurse_reserve").toString()
            +jdbc.queryForList("SELECT id,order_flag,staff_id FROM `order`").toString()
            +jdbc.queryForList("SELECT id,del_flag FROM accident").toString()
            +jdbc.queryForList("SELECT id,real_return_date FROM outward").toString()
            +jdbc.queryForObject("SELECT COUNT(*) FROM consume",Long.class);
    }
}
