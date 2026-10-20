package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;

/** More than one hundred rows and actual prepared SQL, confined to test-owned IDs. */
@EnabledIfEnvironmentVariable(named="SCOPE_HTTP_REDIS_PORT", matches="[0-9]+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}","spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
        "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}","spring.redis.password=scope-redis-only",
        "ai.rag.enabled=false","ai.rag.retry.enabled=false","ai.alert.scan.enabled=false",
        "ai.provider.enabled=false","security.password-reset.email-enabled=false","spring.quartz.auto-startup=false"})
class ElderPaginationHttpIntegrationTest {
    private static final long ROLE=873000, STAFF=873001, OUTSIDER=873002, ADMIN=873003,
            CARE=873100, PRE=873300, FOREIGN_CARE=873501, FOREIGN_PRE=873502, INACTIVE=873505,
            STRUCTURE=873600, BEDS=873700;
    private static final int COUNT=105;
    private static final String PREFIX="页873", PASSWORD="pagination-test-only";
    private static final String NORMAL="/checkContract/pageSearchElderByKey", AI="/ai/scope/elders",
            RECORDS="/elderRecord/pageElderByKey", OUTWARD="/outward/pageSearchElderByKey",
            RETREAT="/retreatApply/pageSearchElderByKey";
    private static final List<String> ENDPOINTS=Arrays.asList(NORMAL,AI,RECORDS,OUTWARD,RETREAT);
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PreparedElderSql sql;
    private String token, outsiderToken, adminToken;
    private boolean created;

    @BeforeEach void seedOwnedRows() {
        assertThat(number("SELECT COUNT(*) FROM role WHERE id=?",ROLE)).isZero();
        assertThat(number("SELECT COUNT(*) FROM staff WHERE id BETWEEN ? AND ?",STAFF,ADMIN)).isZero();
        assertThat(number("SELECT COUNT(*) FROM elder WHERE id BETWEEN ? AND ?",CARE,INACTIVE)).isZero();
        assertThat(number("SELECT COUNT(*) FROM bed WHERE id BETWEEN ? AND ?",BEDS,BEDS+COUNT)).isZero();
        for(String table:Arrays.asList("room_type","building","floor","room"))
            assertThat(number("SELECT COUNT(*) FROM "+table+" WHERE id=?",STRUCTURE)).isZero();
        created=true;
        jdbc.update("INSERT INTO role(id,name,create_id,create_time,update_id,update_time) VALUES(?,'page-role',1,NOW(),1,NOW())",ROLE);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) "
                +"SELECT ?,id,1,NOW(),1,NOW() FROM auth",ROLE);
        String password=new BCryptPasswordEncoder().encode(PASSWORD);
        for(long id:Arrays.asList(STAFF,OUTSIDER,ADMIN))
            jdbc.update("INSERT INTO staff(id,role_id,name,id_num,age,sex,phone,email,pass,avator,address,leave_flag,"
                            +"create_id,create_time,update_id,update_time) VALUES(?,?,'page-staff',?,30,'女',?,'page@example.invalid',?,'','fixture','N',1,NOW(),1,NOW())",
                    id,id==ADMIN?1L:ROLE,identity(id),phone(id),password);
        jdbc.update("INSERT INTO room_type(id,name,month_price,del_flag,create_id,create_time,update_id,update_time) VALUES(?,'page-type',100,'N',?,NOW(),?,NOW())",STRUCTURE,ADMIN,ADMIN);
        jdbc.update("INSERT INTO building(id,name,floor_num,del_flag,create_id,create_time,update_id,update_time) VALUES(?,'page-build',1,'N',?,NOW(),?,NOW())",STRUCTURE,ADMIN,ADMIN);
        jdbc.update("INSERT INTO floor(id,building_id,name,room_num,del_flag,create_id,create_time,update_id,update_time) VALUES(?,?,'page-floor',1,'N',?,NOW(),?,NOW())",STRUCTURE,STRUCTURE,ADMIN,ADMIN);
        jdbc.update("INSERT INTO room(id,type_id,floor_id,name,bed_num,del_flag,create_id,create_time,update_id,update_time) VALUES(?,?,?,'page-room',106,'N',?,NOW(),?,NOW())",STRUCTURE,STRUCTURE,STRUCTURE,ADMIN,ADMIN);
        List<Object[]> beds=new ArrayList<>(), elders=new ArrayList<>(), assignments=new ArrayList<>();
        for(int i=0;i<COUNT;i++) {
            String state=i<5?"退住审核":"入住";
            beds.add(new Object[]{BEDS+i,STRUCTURE,state,ADMIN,ADMIN});
            elders.add(elder(CARE+i,BEDS+i,state,"医",i));
            elders.add(elder(PRE+i,null,i%3==0?"咨询中":i%3==1?"意向跟进":"已退住","售",i));
            assignments.add(new Object[]{CARE+i,STAFF,ADMIN,ADMIN});
            assignments.add(new Object[]{PRE+i,STAFF,ADMIN,ADMIN});
        }
        beds.add(new Object[]{BEDS+COUNT,STRUCTURE,"入住",ADMIN,ADMIN});
        elders.add(elder(FOREIGN_CARE,BEDS+COUNT,"入住","外",0));
        elders.add(elder(FOREIGN_PRE,null,"咨询中","外",1));
        elders.add(elder(INACTIVE,null,"已失效","废",0));
        assignments.add(new Object[]{INACTIVE,STAFF,ADMIN,ADMIN});
        jdbc.batchUpdate("INSERT INTO bed(id,room_id,name,bed_flag,del_flag,create_id,create_time,update_id,update_time) VALUES(?,?,'page-bed',?,'N',?,NOW(),?,NOW())",beds);
        jdbc.batchUpdate("INSERT INTO elder(id,bed_id,name,id_num,age,sex,phone,address,balance,check_flag,create_id,create_time,update_id,update_time) "
                +"VALUES(?,?,?,?,70,?,?,'fixture',0,?,?,NOW(),?,NOW())",elders);
        jdbc.batchUpdate("INSERT INTO elder_staff_assignment(elder_id,staff_id,active,create_id,update_id) VALUES(?,?,'Y',?,?)",assignments);
        for(int i=0;i<17;i++) jdbc.update("INSERT INTO outward(elder_id,chaperone_name,chaperone_phone,chaperone_type,outward_date,plan_return_date,"
                        +"real_return_date,del_flag,create_id,create_time,update_id,update_time) VALUES(?,'fixture','19900000001','家属',NOW(),NOW(),?,?,?,NOW(),?,NOW())",
                CARE+i,i==15?new java.util.Date():null,i==16?"Y":"N",ADMIN,ADMIN);
        // The legacy pending rows whose elders still say 入住 deliberately exercise
        // NOT EXISTS independently from the normal 退住审核 state exclusion.
        for(int i=0;i<22;i++) jdbc.update("INSERT INTO retreat_apply(elder_id,apply_flag,create_id,create_time,update_id,update_time) VALUES(?,?,?,NOW(),?,NOW())",
                CARE+i,i<20?"待审核":i==20?"未通过":"已通过",ADMIN,ADMIN);
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false); factory.setConnectTimeout(10000); factory.setReadTimeout(40000);
        http.getRestTemplate().setRequestFactory(factory);
        token=login(STAFF); outsiderToken=login(OUTSIDER); adminToken=login(ADMIN);
        sql.clear();
    }

    @AfterEach void cleanupOwnedRows() {
        if(!created) return;
        for(String value:Arrays.asList(token,outsiderToken,adminToken))
            if(value!=null) call(HttpMethod.DELETE,"/account/logout",value,null);
        for(String table:Arrays.asList("outward","retreat_apply","elder_staff_assignment"))
            jdbc.update("DELETE FROM "+table+" WHERE elder_id BETWEEN ? AND ?",CARE,INACTIVE);
        jdbc.update("DELETE FROM elder WHERE id BETWEEN ? AND ? AND create_id=?",CARE,INACTIVE,ADMIN);
        jdbc.update("DELETE FROM bed WHERE id BETWEEN ? AND ? AND create_id=?",BEDS,BEDS+COUNT,ADMIN);
        for(String table:Arrays.asList("room","floor","building","room_type")) jdbc.update("DELETE FROM "+table+" WHERE id=? AND create_id=?",STRUCTURE,ADMIN);
        jdbc.update("DELETE FROM ai_audit_log WHERE operator_id BETWEEN ? AND ?",STAFF,ADMIN);
        jdbc.update("DELETE FROM role_auth WHERE role_id=?",ROLE);
        jdbc.update("DELETE FROM staff WHERE id BETWEEN ? AND ?",STAFF,ADMIN);
        jdbc.update("DELETE FROM role WHERE id=?",ROLE);
        sql.clear();
    }

    @Test void allFiveHotspotsUseDatabaseCountAndLimitAcrossFullFirstMiddleAndLastPages() {
        for(String endpoint:ENDPOINTS) {
            List<Long> expected=expected(endpoint);
            int last=(expected.size()+9)/10;
            for(int page:Arrays.asList(1,2,last)) {
                JsonNode data=page(endpoint,token,page,10,null);
                assertThat(data.path("total").asLong()).as(endpoint).isEqualTo(expected.size());
                assertThat(data.path("pages").asInt()).isEqualTo(last);
                List<Long> actual=ids(data);
                assertThat(actual).as(endpoint+" page="+page).isEqualTo(expected.subList((page-1)*10,Math.min(page*10,expected.size())));
                assertThat(data.path("size").asInt()).isEqualTo(actual.size());
                assertThat(data.path("list").get(0).path("rank").asInt()).isEqualTo((page-1)*10+1);
                assertBoundedSql(endpoint,true);
            }
        }
    }

    @Test void scopeIsLiveForEveryCountAndPageAndForgedStaffIdCannotExpandIt() {
        for(String endpoint:ENDPOINTS) {
            JsonNode empty=page(endpoint,outsiderToken,1,10,"staffId="+ADMIN);
            assertThat(empty.path("total").asLong()).as(endpoint).isZero();
            assertThat(ids(empty)).isEmpty();
            assertBoundedSql(endpoint,false);
            JsonNode admin=page(endpoint,adminToken,1,10,null);
            assertThat(admin.path("total").asLong()).as(endpoint).isEqualTo(expected(endpoint).size()+(RECORDS.equals(endpoint)?2:1));
        }
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE staff_id=? AND elder_id IN (?,?)",STAFF,CARE+25,PRE+25);
        for(String endpoint:ENDPOINTS) {
            JsonNode after=page(endpoint,token,1,10,null);
            assertThat(after.path("total").asLong()).as(endpoint).isEqualTo(expected(endpoint).size()-(RECORDS.equals(endpoint)?2:1));
            String filter="phone="+phone(NORMAL.equals(endpoint)?PRE+25:CARE+25);
            if(RECORDS.equals(endpoint)) filter="idNum="+identity(CARE+25);
            assertThat(page(endpoint,token,1,10,filter).path("total").asLong()).as(endpoint+" revoked exact match").isZero();
        }
    }

    @Test void outwardAndRetreatExclusionsApplyBeforePaginationAndReturnedOrDeletedHistoryDoesNotHideResidents() {
        JsonNode outward=page(OUTWARD,token,1,10,null);
        assertThat(outward.path("total").asLong()).isEqualTo(90);
        assertThat(ids(outward)).containsExactlyElementsOf(range(CARE+15,CARE+25));
        assertBoundedSql(OUTWARD,true);
        JsonNode retreat=page(RETREAT,token,1,10,null);
        assertThat(retreat.path("total").asLong()).isEqualTo(85);
        assertThat(ids(retreat)).containsExactlyElementsOf(range(CARE+20,CARE+30));
        assertBoundedSql(RETREAT,true);
    }

    @Test void paginationBoundsAndSearchFiltersKeepTheSameServerSideScope() {
        JsonNode capped=page(AI,token,-1,500,null);
        assertThat(capped.path("pageNum").asInt()).isEqualTo(1);
        assertThat(capped.path("pageSize").asInt()).isEqualTo(100);
        assertThat(ids(capped)).hasSize(100);
        assertThat(capped.path("total").asLong()).isEqualTo(105);
        assertBoundedSql(AI,true);
        JsonNode exact=page(RECORDS,token,1,10,"idNum="+identity(CARE+10)+"&elderSex=女");
        assertThat(exact.path("total").asLong()).isEqualTo(1);
        assertThat(ids(exact)).containsExactly(CARE+10);
        JsonNode wrongSex=page(RECORDS,token,1,10,"idNum="+identity(CARE+10)+"&elderSex=男");
        assertThat(wrongSex.path("total").asLong()).isZero();
        JsonNode exactPhone=page(AI,token,1,10,"phone="+phone(CARE+16));
        assertThat(ids(exactPhone)).containsExactly(CARE+16);
    }

    private Object[] elder(long id,Object bed,String flag,String group,int index) {
        return new Object[]{id,bed,PREFIX+group+String.format("%03d",index),identity(id),index%2==0?"女":"男",phone(id),flag,ADMIN,ADMIN};
    }
    private String identity(long id) { return "990101195001"+id; }
    private String phone(long id) { return "19900"+id; }
    private List<Long> range(long start,long end) { return LongStream.range(start,end).boxed().collect(Collectors.toList()); }
    private List<Long> expected(String endpoint) {
        if(NORMAL.equals(endpoint)) return range(PRE,PRE+COUNT);
        if(AI.equals(endpoint)) return range(CARE,CARE+COUNT);
        if(OUTWARD.equals(endpoint)) return range(CARE+15,CARE+COUNT);
        if(RETREAT.equals(endpoint)) return range(CARE+20,CARE+COUNT);
        List<Long> all=range(CARE,CARE+COUNT); all.addAll(range(PRE,PRE+COUNT)); return all;
    }
    private JsonNode page(String endpoint,String auth,int page,int size,String extra) {
        sql.clear();
        String filter=RECORDS.equals(endpoint)?"elderName":"name";
        String path=UriComponentsBuilder.fromPath(endpoint).queryParam("pageNum",page).queryParam("pageSize",size)
                .queryParam(filter,PREFIX).build().encode().toUriString();
        if(extra!=null) path+="&"+UriComponentsBuilder.fromPath("").query(extra).build().encode().toUriString().replaceFirst("^\\?","");
        JsonNode result=call(HttpMethod.GET,path,auth,null); ok(result); return result.path("data");
    }
    private void assertBoundedSql(String endpoint,boolean expectRows) {
        List<String> statements=sql.snapshot();
        assertThat(statements).as(endpoint+" must execute SQL against elder").isNotEmpty();
        List<String> counts=statements.stream().filter(query -> query.matches("(?s).*\\bcount\\s*\\(.*")).collect(Collectors.toList());
        List<String> rows=statements.stream().filter(query -> !query.matches("(?s).*\\bcount\\s*\\(.*")).collect(Collectors.toList());
        assertThat(counts).as(endpoint+" database COUNT rather than loading all elders").hasSize(1);
        if(expectRows) assertThat(rows).as(endpoint+" database page query").hasSize(1);
        for(String query:rows) assertThat(query).as("unbounded elder row SELECT: "+query).contains(" limit ");
        if(OUTWARD.equals(endpoint)||RETREAT.equals(endpoint))
            for(String query:statements) assertThat(query).as("exclusions must be in COUNT and page SQL").contains("not exists");
    }
    private long number(String query,Object... args) { return jdbc.queryForObject(query,Long.class,args); }
    private List<Long> ids(JsonNode data) { List<Long> result=new ArrayList<>(); for(JsonNode row:data.path("list")) result.add(row.path("id").asLong()); return result; }
    private String login(long id) {
        JsonNode result=call(HttpMethod.POST,"/account/login",null,map("phone",phone(id),"pass",PASSWORD)); ok(result);
        String value=result.path("data").path("token").asText(); assertThat(value).isNotBlank(); return value;
    }
    private JsonNode call(HttpMethod method,String path,String auth,Object body) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if(auth!=null) headers.set("token",auth);
        JsonNode result=http.exchange(URI.create(http.getRootUri()+path),method,new HttpEntity<>(body,headers),JsonNode.class).getBody();
        assertThat(result).as(method+" "+path).isNotNull(); return result;
    }
    private void ok(JsonNode result) { assertThat(result.path("code").asInt()).as(result.toString()).isEqualTo(200); }
    private static Map<String,Object> map(Object... entries) {
        Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<entries.length;i+=2) result.put((String)entries[i],entries[i+1]); return result;
    }
    @TestConfiguration static class SqlEvidenceConfig { @Bean PreparedElderSql preparedElderSql() { return new PreparedElderSql(); } }
    @Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
    static class PreparedElderSql implements Interceptor {
        private final List<String> statements=new CopyOnWriteArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String query=((StatementHandler)invocation.getTarget()).getBoundSql().getSql().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
            if(query.matches("(?s).*\\bfrom\\s+`?elder`?\\b.*")) statements.add(query);
            return invocation.proceed();
        }
        @Override public Object plugin(Object target) { return Plugin.wrap(target,this); }
        @Override public void setProperties(Properties properties) { }
        void clear() { statements.clear(); }
        List<String> snapshot() { return new ArrayList<>(statements); }
    }
}
