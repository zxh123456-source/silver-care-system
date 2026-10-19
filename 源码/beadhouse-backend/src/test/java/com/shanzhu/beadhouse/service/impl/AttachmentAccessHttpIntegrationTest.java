package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SCOPE_HTTP_REDIS_PORT", matches="[0-9]+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}", "spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
        "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}", "spring.redis.password=scope-redis-only",
        "ai.rag.enabled=false", "ai.provider.enabled=false", "security.password-reset.email-enabled=false",
        "filesave.windows=./target/attachment-access", "filesave.linux=./target/attachment-access",
        "filesave.macos=./target/attachment-access", "spring.quartz.auto-startup=false"})
class AttachmentAccessHttpIntegrationTest {
    private static final long ROLE=864000, OWN=864001, OTHER=864002, ADMIN=864003, FIRST=864101, SECOND=864102;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    private final Set<Long> attachments=new LinkedHashSet<>();
    private final Set<Path> uploadedFiles=new LinkedHashSet<>();
    private final List<String> tokens=new ArrayList<>();
    private String own, other, admin;
    private boolean created;

    @BeforeEach void fixture() {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false); factory.setReadTimeout(30000);
        http.getRestTemplate().setRequestFactory(factory);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE id IN (?,?,?)",Long.class,OWN,OTHER,ADMIN)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder WHERE id IN (?,?)",Long.class,FIRST,SECOND)).isZero();
        created=true;
        jdbc.update("INSERT INTO role(id,name,create_id,create_time,update_id,update_time) VALUES(?,'file-scope',1,NOW(),1,NOW())",ROLE);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) SELECT ?,id,1,NOW(),1,NOW() FROM auth",ROLE);
        String hash=new BCryptPasswordEncoder().encode("attachment-fixture-only");
        for(long id:Arrays.asList(OWN,OTHER,ADMIN)) {
            jdbc.update("INSERT INTO staff(id,role_id,name,id_num,age,sex,phone,email,pass,avator,address,leave_flag,create_id,create_time,update_id,update_time) "
                            +"VALUES(?,?,'fa-staff',?,30,'女',?,?,?,'legacy-avatar','fixture','N',1,NOW(),1,NOW())",
                    id,id==ADMIN?1L:ROLE,"990101199001"+id,phone(id),"fixture"+id+"@example.invalid",hash);
        }
        for(long id:Arrays.asList(FIRST,SECOND))
            jdbc.update("INSERT INTO elder(id,name,id_num,age,sex,phone,address,balance,check_flag,create_id,create_time,update_id,update_time) "
                    +"VALUES(?,'fa-elder',?,70,'女','19900002001','fixture',100,'入住',?,NOW(),?,NOW())",id,"990101195001"+id,OTHER,OTHER);
        assign(FIRST,OWN); assign(SECOND,OTHER);
        own=login(OWN); other=login(OTHER); admin=login(ADMIN);
    }

    @AfterEach void cleanup() throws Exception {
        if(!created)return;
        jdbc.execute("DROP TRIGGER IF EXISTS attachment_test_reject_reference");
        for(String token:tokens) call(HttpMethod.DELETE,"/account/logout",token,null);
        for(Long id:attachments) jdbc.update("DELETE FROM attachment_reference WHERE attachment_id=?",id);
        jdbc.update("DELETE ap FROM active_participant ap JOIN active a ON a.id=ap.active_id WHERE a.create_id IN (?,?,?)",OWN,OTHER,ADMIN);
        jdbc.update("DELETE FROM active WHERE create_id IN (?,?,?)",OWN,OTHER,ADMIN);
        jdbc.update("DELETE FROM accident WHERE elder_id IN (?,?)",FIRST,SECOND);
        for(Long id:attachments) jdbc.update("DELETE FROM base_attachment WHERE id=?",id);
        jdbc.update("DELETE FROM elder_staff_assignment WHERE elder_id IN (?,?)",FIRST,SECOND);
        jdbc.update("DELETE FROM elder WHERE id IN (?,?)",FIRST,SECOND);
        jdbc.update("DELETE FROM ai_audit_log WHERE create_id IN (?,?,?)",OWN,OTHER,ADMIN);
        jdbc.update("DELETE FROM role_auth WHERE role_id=?",ROLE);
        jdbc.update("DELETE FROM staff WHERE id IN (?,?,?)",OWN,OTHER,ADMIN);
        jdbc.update("DELETE FROM role WHERE id=?",ROLE);
        Path root=Paths.get("target/attachment-access").toRealPath();
        for(Path path:uploadedFiles) {
            if(Files.exists(path)) {
                assertThat(path.toRealPath().startsWith(root)).isTrue();
                Files.delete(path);
            }
        }
    }

    @Test void draftFilesRequireIdentityAndNeverExposeAnonymousDiskAliases() throws Exception {
        JsonNode photo=upload(own);
        assertThat(content(photo,null).getStatusCodeValue()).isEqualTo(401);
        readable(photo,own); readable(photo,admin);
        assertThat(content(photo,other).getStatusCodeValue()).isEqualTo(403);
        Path stored=Paths.get(jdbc.queryForObject("SELECT path FROM base_attachment WHERE id=?",String.class,photo.path("id").asLong()));
        String oldUrl="/"+Paths.get("target/attachment-access").toAbsolutePath().normalize().relativize(stored.toAbsolutePath().normalize()).toString().replace('\\','/');
        assertThat(http.getForEntity(oldUrl,byte[].class).getStatusCodeValue()).isEqualTo(401);
        ResponseEntity<byte[]> alias=http.exchange(oldUrl,HttpMethod.GET,new HttpEntity<>(headers(own)),byte[].class);
        assertThat(alias.getBody()).isNotEqualTo(png());
        code(call(HttpMethod.GET,"/file/resolve?url="+photo.path("url").asText(),other,null),403);
        assertThat(ok(call(HttpMethod.GET,"/file/resolve?url="+photo.path("url").asText(),own,null)).path("id").asLong())
                .isEqualTo(photo.path("id").asLong());
    }

    @Test void boundFilesFollowRevocationAndNeverBecomeUploaderDraftsAfterReplacementOrDeletion() throws Exception {
        JsonNode photo=upload(own); long accident=addAccident(own,photo,FIRST);
        readable(photo,own);
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE elder_id=? AND staff_id=?",FIRST,OWN);
        assertThat(content(photo,own).getStatusCodeValue()).isEqualTo(403);
        readable(photo,admin);
        jdbc.update("UPDATE elder_staff_assignment SET active='Y' WHERE elder_id=? AND staff_id=?",FIRST,OWN);
        JsonNode replacement=upload(own);
        ok(call(HttpMethod.PUT,"/accident/editAccident",own,accidentBody(accident,FIRST,replacement.path("url").asText())));
        assertThat(content(photo,own).getStatusCodeValue()).isEqualTo(403);
        readable(replacement,own);
        ok(call(HttpMethod.DELETE,"/accident/deleteAccident?accidentId="+accident,own,null));
        assertThat(content(replacement,own).getStatusCodeValue()).isEqualTo(403);
        for(JsonNode image:Arrays.asList(photo,replacement)) {
            assertThat(jdbc.queryForObject("SELECT binding_state FROM base_attachment WHERE id=?",String.class,image.path("id").asLong())).isNotEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment_reference WHERE attachment_id=?",Long.class,image.path("id").asLong())).isEqualTo(1);
        }
    }

    @Test void foreignDraftsAndCrossOwnerReuseCannotLaunderAttachmentAccess() throws Exception {
        JsonNode foreign=upload(other), bound=upload(own);
        addAccident(own,bound,FIRST);
        String before=fingerprint();
        code(call(HttpMethod.POST,"/accident/addAccident",own,accidentBody(null,FIRST,foreign.path("url").asText())),403);
        code(call(HttpMethod.PUT,"/staff/editStaff",own,staffBody(OTHER,bound.path("url").asText())),403);
        assertThat(call(HttpMethod.POST,"/accident/addAccident",own,accidentBody(null,FIRST,"https://untrusted.invalid/photo.png")).path("code").asInt()).isNotEqualTo(200);
        assertThat(fingerprint()).isEqualTo(before);
        readable(foreign,other); readable(bound,own);
    }

    @Test void referenceFailureRollsBackBusinessAndClaimThenTheSameDraftCanBeRetried() throws Exception {
        JsonNode photo=upload(own); String before=fingerprint();
        jdbc.execute("CREATE TRIGGER attachment_test_reject_reference BEFORE INSERT ON attachment_reference FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture reference failure'");
        try {
            assertThat(call(HttpMethod.POST,"/accident/addAccident",own,accidentBody(null,FIRST,photo.path("url").asText())).path("code").asInt()).isNotEqualTo(200);
            assertThat(fingerprint()).isEqualTo(before);
        } finally { jdbc.execute("DROP TRIGGER attachment_test_reject_reference"); }
        readable(photo,own);
        addAccident(own,photo,FIRST);
        readable(photo,own);
    }

    @Test void activityPhotosRequireEveryParticipantAndCannotBeGrantedByRemovingAnUnassignedResident() throws Exception {
        JsonNode photo=upload(admin);
        Map<String,Object> body=activityBody(null,photo.path("url").asText(),Arrays.asList(FIRST,SECOND));
        ok(call(HttpMethod.POST,"/active/addActive",admin,body));
        long activity=jdbc.queryForObject("SELECT id FROM active WHERE name=?",Long.class,body.get("name"));
        assertThat(content(photo,own).getStatusCodeValue()).isEqualTo(403);
        code(call(HttpMethod.GET,"/active/getActiveById?activeId="+activity,own,null),403);
        assertThat(ok(call(HttpMethod.GET,"/active/pageActiveByKey?name=fa-activity&pageNum=1&pageSize=10",own,null)).path("total").asInt()).isZero();
        String before=fingerprint();
        code(call(HttpMethod.PUT,"/active/editActive",own,activityBody(activity,photo.path("url").asText(),Collections.singletonList(FIRST))),403);
        assertThat(fingerprint()).isEqualTo(before);
        assign(SECOND,OWN);
        readable(photo,own);
        assertThat(ok(call(HttpMethod.GET,"/active/getActiveById?activeId="+activity,own,null)).path("participateElderVoList").size()).isEqualTo(2);
        assertThat(ok(call(HttpMethod.GET,"/active/pageActiveByKey?name=fa-activity&pageNum=1&pageSize=10",own,null)).path("total").asInt()).isEqualTo(1);
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE elder_id=? AND staff_id=?",SECOND,OWN);
        assertThat(content(photo,own).getStatusCodeValue()).isEqualTo(403);
    }

    @Test void moduleRevocationAppliesToBoundContentInTheExistingSession() throws Exception {
        JsonNode photo=upload(own); addAccident(own,photo,FIRST); readable(photo,own);
        jdbc.update("DELETE ra FROM role_auth ra JOIN auth a ON a.id=ra.auth_id WHERE ra.role_id=? AND a.url='/check-in/accident'",ROLE);
        assertThat(content(photo,own).getStatusCodeValue()).isEqualTo(403);
        readable(photo,admin);
    }

    @Test void aDraftCanBeClaimedByOnlyOneOfTwoConcurrentBusinessRecords() throws Exception {
        JsonNode photo=upload(own); long id=photo.path("id").asLong();
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM accident WHERE elder_id=?",Long.class,FIRST);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        List<Future<JsonNode>> results=new ArrayList<>();
        try {
            try(Connection blocker=dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                try(PreparedStatement statement=blocker.prepareStatement("SELECT id FROM base_attachment WHERE id=? FOR UPDATE")) {
                    statement.setLong(1,id); try(ResultSet row=statement.executeQuery()) { assertThat(row.next()).isTrue(); }
                    for(int i=0;i<2;i++) results.add(pool.submit(()->call(HttpMethod.POST,"/accident/addAccident",own,accidentBody(null,FIRST,photo.path("url").asText()))));
                    long waiting=0,deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                    while(System.nanoTime()<deadline) {
                        waiting=jdbc.queryForObject("SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w "
                                +"JOIN performance_schema.data_locks b ON b.ENGINE=w.ENGINE AND b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                                +"WHERE b.OBJECT_SCHEMA=DATABASE() AND b.OBJECT_NAME='base_attachment' AND b.INDEX_NAME='PRIMARY' AND b.LOCK_DATA=?",Long.class,String.valueOf(id));
                        if(waiting>=2)break;
                        Thread.sleep(25);
                    }
                    assertThat(waiting).as("both business transactions reach the attachment claim barrier").isEqualTo(2);
                } finally { blocker.rollback(); }
            }
            List<Integer> codes=new ArrayList<>();
            for(Future<JsonNode> result:results) codes.add(result.get(30,TimeUnit.SECONDS).path("code").asInt());
            assertThat(codes).containsExactlyInAnyOrder(200,403);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accident WHERE elder_id=?",Long.class,FIRST)).isEqualTo(before+1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment_reference WHERE attachment_id=? AND active='Y'",Long.class,id)).isEqualTo(1);
            readable(photo,own);
        } finally { pool.shutdown(); assertThat(pool.awaitTermination(35,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void contentCannotReadOutsideTheStorageRootOrExposeMissingFilePaths() throws Exception {
        JsonNode photo=upload(own); long id=photo.path("id").asLong();
        String original=jdbc.queryForObject("SELECT path FROM base_attachment WHERE id=?",String.class,id);
        Path outside=Paths.get("target","attachment-outside-"+UUID.randomUUID()+".txt").toAbsolutePath().normalize();
        Files.write(outside,"private outside fixture".getBytes("UTF-8"));
        try {
            jdbc.update("UPDATE base_attachment SET path=? WHERE id=?",outside.toString(),id);
            ResponseEntity<byte[]> denied=content(photo,own);
            assertThat(new String(denied.getBody(),"UTF-8")).doesNotContain("private outside fixture",outside.toString());
            assertThat(denied.getHeaders().getContentType().isCompatibleWith(MediaType.IMAGE_PNG)).isFalse();
            jdbc.update("UPDATE base_attachment SET path=? WHERE id=?",original+".missing",id);
            ResponseEntity<byte[]> missing=content(photo,own);
            assertThat(new String(missing.getBody(),"UTF-8")).doesNotContain(original);
            assertThat(missing.getBody()).isNotEqualTo(png());
        } finally { jdbc.update("UPDATE base_attachment SET path=? WHERE id=?",original,id); Files.deleteIfExists(outside); }
    }

    @Test void legacyBackfillIsRepeatableAndNeverTreatsSharedOrUnknownFilesAsDrafts() throws Exception {
        JsonNode unique=upload(own), shared=upload(own), unknown=upload(own);
        for(JsonNode image:Arrays.asList(unique,shared,unknown))
            jdbc.update("UPDATE base_attachment SET binding_state='LEGACY',media_type=NULL,url=? WHERE id=?",legacyUrl(image),image.path("id").asLong());
        for(JsonNode image:Arrays.asList(unique,shared))
            jdbc.update("INSERT INTO accident(elder_id,staff_id,occur_date,description,picture,del_flag,create_id,create_time,update_id,update_time) "
                    +"VALUES(?,?,NOW(),'legacy fixture',?,'N',?,NOW(),?,NOW())",FIRST,OWN,legacyUrl(image),OWN,OWN);
        jdbc.update("UPDATE staff SET avator=? WHERE id=?",legacyUrl(shared),OTHER);
        runMigration();
        readable(unique,own);
        assertThat(content(shared,own).getStatusCodeValue()).isEqualTo(403);
        assertThat(content(unknown,own).getStatusCodeValue()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT binding_state FROM base_attachment WHERE id=?",String.class,unique.path("id").asLong())).isEqualTo("BOUND");
        assertThat(jdbc.queryForObject("SELECT binding_state FROM base_attachment WHERE id=?",String.class,shared.path("id").asLong())).isEqualTo("CONFLICT");
        assertThat(jdbc.queryForObject("SELECT binding_state FROM base_attachment WHERE id=?",String.class,unknown.path("id").asLong())).isEqualTo("LEGACY");
        String before=fingerprint(); runMigration(); assertThat(fingerprint()).isEqualTo(before);
        code(call(HttpMethod.GET,"/file/diagnostics?limit=100",own,null),403);
        JsonNode report=ok(call(HttpMethod.GET,"/file/diagnostics?afterId=0&limit=100",admin,null));
        assertThat(report.toString()).doesNotContain("C:\\", "target/attachment-access", "http://", "https://");
    }

    @Test void duplicateMetadataAndDeletedOwnersRemainQuarantinedAcrossRepeatedMigration() throws Exception {
        JsonNode first=upload(own),duplicate=upload(own),retired=upload(own);
        String sameUrl=legacyUrl(first);
        for(JsonNode image:Arrays.asList(first,duplicate,retired))
            jdbc.update("UPDATE base_attachment SET binding_state='LEGACY',media_type=NULL,url=? WHERE id=?",
                    image==retired?legacyUrl(retired):sameUrl,image.path("id").asLong());
        for(JsonNode image:Arrays.asList(first,retired))
            jdbc.update("INSERT INTO accident(elder_id,staff_id,occur_date,description,picture,del_flag,create_id,create_time,update_id,update_time) "
                    +"VALUES(?,?,NOW(),'legacy ambiguous fixture',?,?,?,NOW(),?,NOW())",FIRST,OWN,legacyUrl(image),image==retired?"Y":"N",OWN,OWN);
        runMigration();
        for(JsonNode image:Arrays.asList(first,duplicate)) {
            assertThat(jdbc.queryForObject("SELECT binding_state FROM base_attachment WHERE id=?",String.class,image.path("id").asLong())).isEqualTo("CONFLICT");
            assertThat(content(image,own).getStatusCodeValue()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT active FROM attachment_reference WHERE attachment_id=?",String.class,retired.path("id").asLong())).isEqualTo("N");
        assertThat(content(retired,own).getStatusCodeValue()).isEqualTo(403);
        jdbc.update("UPDATE accident SET del_flag='N' WHERE elder_id=? AND picture=?",FIRST,legacyUrl(retired));
        runMigration();
        assertThat(jdbc.queryForObject("SELECT active FROM attachment_reference WHERE attachment_id=?",String.class,retired.path("id").asLong())).isEqualTo("N");
        assertThat(content(retired,own).getStatusCodeValue()).isEqualTo(403);
        Path missing=Paths.get(jdbc.queryForObject("SELECT path FROM base_attachment WHERE id=?",String.class,retired.path("id").asLong()));
        assertThat(uploadedFiles).contains(missing); Files.delete(missing);
        JsonNode diagnostic=ok(call(HttpMethod.GET,"/file/diagnostics?afterId="+(retired.path("id").asLong()-1)+"&limit=1",admin,null));
        assertThat(diagnostic.path("items").get(0).path("storageStatus").asText()).isEqualTo("MISSING");
    }

    private void runMigration() throws Exception {
        try(Connection connection=dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.support.EncodedResource(
                    new FileSystemResource(Paths.get("../../数据库/attachment_access_upgrade.sql")),"UTF-8"));
        }
    }
    private String legacyUrl(JsonNode image) { return "/upload/img/legacy-fixture-"+image.path("id").asLong()+".png"; }
    private String phone(long id) { return "19900"+id; }
    private void assign(long elder,long staff) { jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active,create_id,update_id) VALUES(?,?,'Y',?,?)",elder,staff,ADMIN,ADMIN); }
    private String login(long staff) {
        String token=ok(call(HttpMethod.POST,"/account/login",null,map("phone",phone(staff),"pass","attachment-fixture-only"))).path("token").asText();
        tokens.add(token); return token;
    }
    private JsonNode upload(String token) throws Exception {
        HttpHeaders headers=headers(token); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        LinkedMultiValueMap<String,Object> body=new LinkedMultiValueMap<>();
        body.add("file",new ByteArrayResource(png()) { @Override public String getFilename(){return "fixture.png";} });
        JsonNode image=ok(http.postForObject("/file/uploadImg",new HttpEntity<>(body,headers),JsonNode.class));
        long id=image.path("id").asLong(); assertThat(id).isPositive(); attachments.add(id);
        uploadedFiles.add(Paths.get(jdbc.queryForObject("SELECT path FROM base_attachment WHERE id=?",String.class,id)));
        return image;
    }
    private byte[] png() throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output); return output.toByteArray();
    }
    private ResponseEntity<byte[]> content(JsonNode image,String token) {
        return http.exchange("/file/"+image.path("id").asLong()+"/content",HttpMethod.GET,new HttpEntity<>(headers(token)),byte[].class);
    }
    private void readable(JsonNode image,String token) throws Exception {
        ResponseEntity<byte[]> response=content(image,token);
        assertThat(response.getStatusCodeValue()).isEqualTo(200); assertThat(response.getBody()).isEqualTo(png());
        assertThat(response.getHeaders().getCacheControl()).contains("private","no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }
    private long addAccident(String token,JsonNode image,long elder) {
        ok(call(HttpMethod.POST,"/accident/addAccident",token,accidentBody(null,elder,image.path("url").asText())));
        return jdbc.queryForObject("SELECT id FROM accident WHERE elder_id=? AND picture=?",Long.class,elder,image.path("url").asText());
    }
    private Map<String,Object> accidentBody(Long id,long elder,String picture) {
        return map("id",id,"elderId",elder,"staffId",OWN,"occurDate",LocalDate.now().toString(),"description","attachment fixture","picture",picture);
    }
    private Map<String,Object> staffBody(long id,String avatar) {
        return map("id",id,"roleId",ROLE,"name","fa-staff","idNum","990101199001"+id,"age",30,"sex","女","phone",phone(id),
                "email","fixture"+id+"@example.invalid","address","fixture","avator",avatar);
    }
    private Map<String,Object> activityBody(Long id,String picture,List<Long> elders) {
        return map("id",id,"typeId",jdbc.queryForObject("SELECT MIN(id) FROM active_type",Long.class),"theme","fixture","name","fa-activity",
                "content","fixture","address","fixture","organizer","fixture","phone","19900002001","activeDate",LocalDate.now().toString(),
                "activePicture",picture,"elderIdList",elders);
    }
    private HttpHeaders headers(String token) { HttpHeaders h=new HttpHeaders(); if(token!=null)h.set("token",token); return h; }
    private JsonNode call(HttpMethod method,String path,String token,Object body) {
        HttpHeaders h=headers(token); h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path,method,new HttpEntity<>(body,h),JsonNode.class).getBody();
    }
    private JsonNode ok(JsonNode result) { return code(result,200).path("data"); }
    private JsonNode code(JsonNode result,int expected) { assertThat(result).isNotNull(); assertThat(result.path("code").asInt()).as(result.toString()).isEqualTo(expected); return result; }
    private Map<String,Object> map(Object... values) { Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]); return result; }
    private String fingerprint() {
        StringBuilder result=new StringBuilder();
        for(Long id:attachments) {
            result.append(jdbc.queryForList("SELECT * FROM base_attachment WHERE id=?",id));
            result.append(jdbc.queryForList("SELECT * FROM attachment_reference WHERE attachment_id=? ORDER BY id",id));
        }
        result.append(jdbc.queryForList("SELECT * FROM accident WHERE elder_id IN (?,?) ORDER BY id",FIRST,SECOND));
        result.append(jdbc.queryForList("SELECT * FROM active WHERE create_id IN (?,?,?) ORDER BY id",OWN,OTHER,ADMIN));
        result.append(jdbc.queryForList("SELECT * FROM staff WHERE id IN (?,?,?) ORDER BY id",OWN,OTHER,ADMIN));
        return result.toString();
    }
}
