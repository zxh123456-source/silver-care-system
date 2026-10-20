package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.shanzhu.beadhouse.service.ReserveService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import javax.sql.DataSource;
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
        "spring.quartz.auto-startup=false"})
class BedLifecycleHttpIntegrationTest {
    private static final long ROLE=865000, STAFF=865001, FIRST=865101, SECOND=865102,
            STRUCTURE=865201, BED1=865301, BED2=865302;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ReserveService reserveService;
    private String token;
    private boolean created;

    @BeforeEach void fixture() {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false); factory.setReadTimeout(40000);
        http.getRestTemplate().setRequestFactory(factory);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE id=?",Long.class,STAFF)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder WHERE id IN (?,?)",Long.class,FIRST,SECOND)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bed WHERE id IN (?,?)",Long.class,BED1,BED2)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role WHERE id=?",Long.class,ROLE)).isZero();
        for(String table:Arrays.asList("building","floor","room","room_type","source"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=?",Long.class,STRUCTURE)).isZero();
        created=true;
        jdbc.update("INSERT INTO role(id,name,create_id,create_time,update_id,update_time) VALUES(?,'bed-role',1,NOW(),1,NOW())",ROLE);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) SELECT ?,id,1,NOW(),1,NOW() FROM auth",ROLE);
        jdbc.update("INSERT INTO staff(id,role_id,name,id_num,age,sex,phone,email,pass,avator,address,leave_flag,create_id,create_time,update_id,update_time) "
                +"VALUES(?,?,'bed-staff','990101199001865001',30,'女','19900865001','bed@example.invalid',?,'fixture','fixture','N',1,NOW(),1,NOW())",
                STAFF,ROLE,new BCryptPasswordEncoder().encode("bed-fixture-only"));
        jdbc.update("INSERT INTO room_type(id,name,month_price,del_flag,create_id,create_time,update_id,update_time) VALUES(?,'bed-type',100,'N',?,NOW(),?,NOW())",STRUCTURE,STAFF,STAFF);
        jdbc.update("INSERT INTO source(id,name,del_flag,create_id,create_time,update_id,update_time) VALUES(?,'bed-source','N',?,NOW(),?,NOW())",STRUCTURE,STAFF,STAFF);
        jdbc.update("INSERT INTO building(id,name,floor_num,del_flag,create_id,create_time,update_id,update_time) VALUES(?,'bed-build',1,'N',?,NOW(),?,NOW())",STRUCTURE,STAFF,STAFF);
        jdbc.update("INSERT INTO floor(id,building_id,name,room_num,del_flag,create_id,create_time,update_id,update_time) VALUES(?,?,'bed-floor',1,'N',?,NOW(),?,NOW())",STRUCTURE,STRUCTURE,STAFF,STAFF);
        jdbc.update("INSERT INTO room(id,type_id,floor_id,name,bed_num,del_flag,create_id,create_time,update_id,update_time) VALUES(?,?,?,'bed-room',2,'N',?,NOW(),?,NOW())",STRUCTURE,STRUCTURE,STRUCTURE,STAFF,STAFF);
        for(long id:Arrays.asList(BED1,BED2)) jdbc.update("INSERT INTO bed(id,room_id,name,bed_flag,del_flag,create_id,create_time,update_id,update_time) VALUES(?,?,'bed-fixture','空闲','N',?,NOW(),?,NOW())",id,STRUCTURE,STAFF,STAFF);
        for(long id:Arrays.asList(FIRST,SECOND)) {
            jdbc.update("INSERT INTO elder(id,name,id_num,age,sex,phone,address,balance,check_flag,create_id,create_time,update_id,update_time) VALUES(?,'bed-elder',?,70,'女','19900002001','fixture',100,'咨询中',?,NOW(),?,NOW())",id,identity(id),STAFF,STAFF);
            jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active,create_id,update_id) VALUES(?,?,'Y',?,?)",id,STAFF,STAFF,STAFF);
        }
        token=expect(call(HttpMethod.POST,"/account/login",map("phone","19900865001","pass","bed-fixture-only")),200).path("data").path("token").asText();
        assertThat(token).isNotBlank();
    }

    @AfterEach void cleanup() {
        if(!created)return;
        for(String table:Arrays.asList("contract","emergency_contact"))jdbc.execute("DROP TRIGGER IF EXISTS bed_test_reject_"+table);
        if(token!=null)call(HttpMethod.DELETE,"/account/logout",null);
        for(String table:Arrays.asList("contract","emergency_contact","reserve","retreat_apply","consult","elder_staff_assignment"))
            jdbc.update("DELETE FROM "+table+" WHERE elder_id IN (SELECT id FROM elder WHERE create_id=?)",STAFF);
        jdbc.update("DELETE FROM elder WHERE create_id=?",STAFF);
        jdbc.update("DELETE FROM bed WHERE create_id=?",STAFF);
        for(String table:Arrays.asList("room","floor","building","room_type","source")) jdbc.update("DELETE FROM "+table+" WHERE create_id=?",STAFF);
        jdbc.update("DELETE FROM ai_audit_log WHERE create_id=?",STAFF);
        jdbc.update("DELETE FROM role_auth WHERE role_id=?",ROLE);
        jdbc.update("DELETE FROM staff WHERE id=?",STAFF);
        jdbc.update("DELETE FROM role WHERE id=?",ROLE);
    }

    @Test void twoResidentsCannotReserveTheSameBedAfterBothReachTheRowLock() throws Exception {
        List<ResponseEntity<JsonNode>> results=race("bed",BED1,
                ()->call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED1)),
                ()->call(HttpMethod.POST,"/reserve/addReserve",reservation(SECOND,BED1)));
        assertOneSuccessOneConflict(results);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder WHERE id IN (?,?) AND bed_id=? AND check_flag='预定'",Long.class,FIRST,SECOND,BED1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reserve WHERE elder_id IN (?,?)",Long.class,FIRST,SECOND)).isEqualTo(1);
        assertThat(bedState(BED1)).isEqualTo("预定");
        assertThat(bedState(BED2)).isEqualTo("空闲");
    }

    @Test void oneResidentCannotReserveTwoBedsAfterBothReachTheElderLock() throws Exception {
        List<ResponseEntity<JsonNode>> results=race("elder",FIRST,
                ()->call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED1)),
                ()->call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED2)));
        assertOneSuccessOneConflict(results);
        long occupied=jdbc.queryForObject("SELECT bed_id FROM elder WHERE id=?",Long.class,FIRST);
        assertThat(occupied).isIn(BED1,BED2);
        assertThat(bedState(occupied)).isEqualTo("预定");
        assertThat(bedState(occupied==BED1?BED2:BED1)).isEqualTo("空闲");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reserve WHERE elder_id=?",Long.class,FIRST)).isEqualTo(1);
    }

    @Test void ownActiveReservationConvertsToAdmissionOnTheSameBedAndKeepsHistory() {
        expect(call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED1)),200);
        long reservation=jdbc.queryForObject("SELECT id FROM reserve WHERE elder_id=?",Long.class,FIRST);
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),200);
        assertThat(elderState(FIRST)).isEqualTo("入住");
        assertThat(bedState(BED1)).isEqualTo("入住");
        assertThat(jdbc.queryForObject("SELECT bed_id FROM elder WHERE id=?",Long.class,FIRST)).isEqualTo(BED1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reserve WHERE id=? AND elder_id=?",Long.class,reservation,FIRST)).isEqualTo(1);
        assertThat(reservationState(reservation)).isEqualTo("CONVERTED");
        assertThat(jdbc.queryForObject("SELECT bed_id FROM reserve WHERE id=?",Long.class,reservation)).isEqualTo(BED1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract WHERE elder_id=?",Long.class,FIRST)).isEqualTo(1);
        String before=fingerprint();
        expect(call(HttpMethod.PUT,"/reserve/refund",map("reserveId",reservation,"elderId",FIRST)),409);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void deletingAnOccupiedContractCannotBypassFormalCheckout() {
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),200);
        String before=fingerprint();
        expect(call(HttpMethod.DELETE,"/checkContract/deleteCheckContract?elderId="+FIRST,null),409);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(elderState(FIRST)).isEqualTo("入住");
        assertThat(bedState(BED1)).isEqualTo("入住");
    }

    @Test void aRejectedOldApplicationCannotApproveANewerCheckoutRound() {
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),200);
        expect(call(HttpMethod.POST,"/retreatApply/addRetreatApply",map("elderId",FIRST)),200);
        long old=jdbc.queryForObject("SELECT MAX(id) FROM retreat_apply WHERE elder_id=?",Long.class,FIRST);
        expect(call(HttpMethod.PUT,"/retreatAudit/auditElderFee",map("applyId",old,"elderId",FIRST,"auditResult","不通过")),200);
        expect(call(HttpMethod.POST,"/retreatApply/addRetreatApply",map("elderId",FIRST)),200);
        long current=jdbc.queryForObject("SELECT MAX(id) FROM retreat_apply WHERE elder_id=?",Long.class,FIRST);
        assertThat(current).isNotEqualTo(old);
        String before=fingerprint();
        expect(call(HttpMethod.PUT,"/retreatAudit/auditElderFee",map("applyId",old,"elderId",FIRST,"auditResult","通过")),409);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(elderState(FIRST)).isEqualTo("退住审核");
        assertThat(bedState(BED1)).isEqualTo("退住审核");
        assertThat(jdbc.queryForObject("SELECT apply_flag FROM retreat_apply WHERE id=?",String.class,current)).isEqualTo("待审核");
    }

    @Test void firstTimeRegistrationWithTheSameIdentityCannotCreateTwoActiveResidents() throws Exception {
        long newIdentity=865103;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder WHERE id_num=?",Long.class,identity(newIdentity))).isZero();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<ResponseEntity<JsonNode>> first=null,second=null;
        try {
            try(Connection blocker=dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                try(PreparedStatement statement=blocker.prepareStatement("SELECT id FROM bed WHERE id=? FOR UPDATE")) {
                    statement.setLong(1,BED1); try(ResultSet rows=statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                    first=pool.submit(()->call(HttpMethod.POST,"/reserve/addReserve",reservation(newIdentity,BED1)));
                    awaitWaiters("bed",BED1,1);
                    second=pool.submit(()->call(HttpMethod.POST,"/reserve/addReserve",reservation(newIdentity,BED2)));
                    long waiting=0,deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                    while(System.nanoTime()<deadline) {
                        waiting=jdbc.queryForObject("SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w "
                                +"JOIN performance_schema.data_locks b ON b.ENGINE=w.ENGINE AND b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                                +"WHERE b.OBJECT_SCHEMA=DATABASE() AND b.OBJECT_NAME IN ('bed','elder')",Long.class);
                        if(waiting>=2 || second.isDone())break;
                        Thread.sleep(25);
                    }
                    assertThat(waiting).as("second identity creation waits for the first uncommitted registration").isEqualTo(2);
                } finally { blocker.rollback(); }
            }
            assertOneSuccessOneConflict(Arrays.asList(first.get(35,TimeUnit.SECONDS),second.get(35,TimeUnit.SECONDS)));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder WHERE id_num=? AND check_flag<>'已失效'",Long.class,identity(newIdentity))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bed WHERE room_id=? AND bed_flag='预定'",Long.class,STRUCTURE)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reserve r JOIN elder e ON e.id=r.elder_id WHERE e.id_num=?",Long.class,identity(newIdentity))).isEqualTo(1);
        } finally { pool.shutdown(); assertThat(pool.awaitTermination(40,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void admissionAndRefundSerializeWithoutReleasingAnOccupiedBedOrChangingTheWallet() throws Exception {
        expect(call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED1)),200);
        long id=reservationId(FIRST);
        List<ResponseEntity<JsonNode>> results=race("elder",FIRST,
                ()->call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),
                ()->call(HttpMethod.PUT,"/reserve/refund",map("reserveId",id,"elderId",FIRST)));
        expect(results.get(0),200);
        String state=reservationState(id);
        assertThat(state).isIn("CONVERTED","REFUNDED");
        expect(results.get(1),"CONVERTED".equals(state)?409:200);
        assertThat(elderState(FIRST)).isEqualTo("入住");
        assertThat(bedState(BED1)).isEqualTo("入住");
        assertThat(jdbc.queryForObject("SELECT bed_id FROM elder WHERE id=?",Long.class,FIRST)).isEqualTo(BED1);
        assertThat(jdbc.queryForObject("SELECT balance FROM elder WHERE id=?",java.math.BigDecimal.class,FIRST)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract WHERE elder_id=?",Long.class,FIRST)).isEqualTo(1);
    }

    @Test void expiryAndAdmissionUseCurrentStateAndKeepAnExpiredReservationAsHistory() throws Exception {
        assertNoUnrelatedDueReservations();
        expect(call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED1)),200);
        long id=reservationId(FIRST);
        jdbc.update("UPDATE reserve SET due_date=DATE_SUB(NOW(),INTERVAL 2 DAY) WHERE id=?",id);
        List<ResponseEntity<JsonNode>> results=race("elder",FIRST,
                ()->call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),
                ()->{reserveService.reserveExpireJob();return null;});
        int code=results.get(0).getBody().path("code").asInt();
        // If expiry commits first, a subsequent ordinary admission is allowed; otherwise the expired conversion is rejected.
        assertThat(code).isIn(200,409);
        assertThat(reservationState(id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT bed_id FROM reserve WHERE id=?",Long.class,id)).isEqualTo(BED1);
        if(code==200) {
            assertThat(elderState(FIRST)).isEqualTo("入住"); assertThat(bedState(BED1)).isEqualTo("入住");
        } else {
            assertThat(elderState(FIRST)).isEqualTo("意向跟进"); assertThat(bedState(BED1)).isEqualTo("空闲");
            assertThat(jdbc.queryForObject("SELECT bed_id FROM elder WHERE id=?",Long.class,FIRST)).isNull();
        }
        String before=fingerprint(); reserveService.reserveExpireJob(); assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void anOldExpiredReservationCannotReleaseEitherResidentsLaterReservation() {
        assertNoUnrelatedDueReservations();
        expect(call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED1)),200);
        long old=reservationId(FIRST);
        jdbc.update("UPDATE reserve SET due_date=DATE_SUB(NOW(),INTERVAL 2 DAY) WHERE id=?",old);
        reserveService.reserveExpireJob();
        assertThat(reservationState(old)).isEqualTo("EXPIRED");
        expect(call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED2)),200);
        expect(call(HttpMethod.POST,"/reserve/addReserve",reservation(SECOND,BED1)),200);
        String before=fingerprint();
        reserveService.reserveExpireJob();
        expect(call(HttpMethod.PUT,"/reserve/refund",map("reserveId",old,"elderId",FIRST)),409);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(bedState(BED1)).isEqualTo("预定"); assertThat(bedState(BED2)).isEqualTo("预定");
        assertThat(jdbc.queryForObject("SELECT bed_id FROM reserve WHERE id=?",Long.class,old)).isEqualTo(BED1);
        JsonNode detail=expect(call(HttpMethod.GET,"/reserve/getReserveByReserveIdAndElderId?reserveId="+old+"&elderId="+FIRST,null),200).path("data");
        assertThat(detail.path("bedId").asLong()).isEqualTo(BED1);
        assertThat(detail.path("state").asText()).isEqualTo("EXPIRED");
    }

    @Test void bedTransferAndCheckoutApplicationSerializeAroundTheSameResident() throws Exception {
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),200);
        List<ResponseEntity<JsonNode>> results=race("elder",FIRST,
                ()->call(HttpMethod.PUT,"/checkContract/editCheckContract",contract(FIRST,BED2)),
                ()->call(HttpMethod.POST,"/retreatApply/addRetreatApply",map("elderId",FIRST)));
        expect(results.get(1),200);
        long current=jdbc.queryForObject("SELECT bed_id FROM elder WHERE id=?",Long.class,FIRST);
        assertThat(current).isIn(BED1,BED2);
        expect(results.get(0),current==BED2?200:409);
        assertThat(elderState(FIRST)).isEqualTo("退住审核");
        assertThat(bedState(current)).isEqualTo("退住审核");
        assertThat(bedState(current==BED1?BED2:BED1)).isEqualTo("空闲");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retreat_apply WHERE elder_id=? AND apply_flag='待审核'",Long.class,FIRST)).isEqualTo(1);
        String before=fingerprint();
        expect(call(HttpMethod.PUT,"/checkContract/editCheckContract",contract(FIRST,current)),409);
        expect(call(HttpMethod.DELETE,"/checkContract/deleteCheckContract?elderId="+FIRST,null),409);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void concurrentCheckoutApplicationsCreateOnlyOnePendingApplication() throws Exception {
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),200);
        assertOneSuccessOneConflict(race("elder",FIRST,
                ()->call(HttpMethod.POST,"/retreatApply/addRetreatApply",map("elderId",FIRST)),
                ()->call(HttpMethod.POST,"/retreatApply/addRetreatApply",map("elderId",FIRST))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retreat_apply WHERE elder_id=?",Long.class,FIRST)).isEqualTo(1);
        assertThat(elderState(FIRST)).isEqualTo("退住审核"); assertThat(bedState(BED1)).isEqualTo("退住审核");
    }

    @Test void concurrentIdenticalApprovalsReplayWithoutASecondMutation() throws Exception {
        long application=enterAndApply();
        List<ResponseEntity<JsonNode>> results=race("elder",FIRST,
                ()->audit(application,"通过"),()->audit(application,"通过"));
        for(ResponseEntity<JsonNode> response:results)expect(response,200);
        assertThat(elderState(FIRST)).isEqualTo("已退住"); assertThat(bedState(BED1)).isEqualTo("空闲");
        assertThat(jdbc.queryForObject("SELECT bed_id FROM elder WHERE id=?",Long.class,FIRST)).isNull();
        assertThat(jdbc.queryForObject("SELECT apply_flag FROM retreat_apply WHERE id=?",String.class,application)).isEqualTo("已通过");
        String before=fingerprint(); expect(audit(application,"通过"),200); assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void conflictingApprovalsHaveOneWinnerAndCannotReverseTheCommittedResult() throws Exception {
        long application=enterAndApply();
        assertOneSuccessOneConflict(race("elder",FIRST,()->audit(application,"通过"),()->audit(application,"不通过")));
        String result=jdbc.queryForObject("SELECT apply_flag FROM retreat_apply WHERE id=?",String.class,application);
        boolean passed="已通过".equals(result);
        assertThat(result).isIn("已通过","未通过");
        assertThat(elderState(FIRST)).isEqualTo(passed?"已退住":"入住");
        assertThat(bedState(BED1)).isEqualTo(passed?"空闲":"入住");
        String before=fingerprint();
        expect(audit(application,passed?"通过":"不通过"),200);
        expect(audit(application,passed?"不通过":"通过"),409);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void anOldSuccessfulApprovalReplaysWithoutReleasingTheNewCheckoutRoundsBed() {
        long old=enterAndApply(); expect(audit(old,"通过"),200);
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED2)),200);
        expect(call(HttpMethod.POST,"/retreatApply/addRetreatApply",map("elderId",FIRST)),200);
        long current=applicationId(); assertThat(current).isNotEqualTo(old);
        String before=fingerprint();
        expect(audit(old,"通过"),200); expect(audit(old,"不通过"),409);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT apply_flag FROM retreat_apply WHERE id=?",String.class,current)).isEqualTo("待审核");
        assertThat(bedState(BED2)).isEqualTo("退住审核");
    }

    @Test void salesEndpointsCannotResetAnOccupiedOrReservedResidentsLifecycle() {
        jdbc.update("INSERT INTO consult(id,elder_id,source_id,staff_id,name,phone,relation,consult_date,consult_content,create_id,create_time,update_id,update_time) "
                +"VALUES(?,?,?,?,'bed-payer','19900002001','亲属',NOW(),'fixture',?,NOW(),?,NOW())",FIRST,FIRST,STRUCTURE,STAFF,STAFF,STAFF);
        expect(call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED1)),200);
        for(String stage:Arrays.asList("预定","入住","退住审核")) {
            if("入住".equals(stage))expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),200);
            if("退住审核".equals(stage))expect(call(HttpMethod.POST,"/retreatApply/addRetreatApply",map("elderId",FIRST)),200);
            assertThat(elderState(FIRST)).isEqualTo(stage);
            String before=fingerprint();
            expect(call(HttpMethod.DELETE,"/consult/deleteConsult?elderId="+FIRST,null),409);
            expect(call(HttpMethod.PUT,"/consult/intentionConsult?elderId="+FIRST,null),409);
            expect(call(HttpMethod.PUT,"/consult/editConsult",map("consultId",FIRST,"elderId",FIRST,"sourceId",STRUCTURE,"staffId",STAFF,
                    "consultName","bed-payer","consultPhone","19900002001","relation","亲属","consultDate",LocalDate.now().toString(),
                    "consultContent","changed","elderName","changed","idNum",identity(FIRST),"age","70","sex","女","elderPhone","19900002001","address","changed")),409);
            expect(call(HttpMethod.POST,"/intention/addIntention",map("name","changed","idNum",identity(FIRST),"age",70,"sex","女","phone","19900002001","address","changed")),409);
            assertThat(fingerprint()).isEqualTo(before);
        }
    }

    @Test void deletingABedAndAdmittingAResidentCannotLeaveADeletedOccupiedBed() throws Exception {
        List<ResponseEntity<JsonNode>> results=race("bed",BED1,
                ()->call(HttpMethod.DELETE,"/build/deleteBed?bedId="+BED1,null),
                ()->call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)));
        assertOneSuccessOneConflict(results);
        boolean deleted="Y".equals(jdbc.queryForObject("SELECT del_flag FROM bed WHERE id=?",String.class,BED1));
        expect(results.get(0),deleted?200:409); expect(results.get(1),deleted?409:200);
        assertThat(elderState(FIRST)).isEqualTo(deleted?"咨询中":"入住");
        assertThat(bedState(BED1)).isEqualTo(deleted?"空闲":"入住");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder WHERE bed_id=?",Long.class,BED1)).isEqualTo(deleted?0:1);
    }

    @Test void deletingARoomAndAddingABedCannotLeaveAnActiveChildInADeletedParent() throws Exception {
        jdbc.update("UPDATE room SET bed_num=3 WHERE id=?",STRUCTURE);
        List<ResponseEntity<JsonNode>> results=race("building",STRUCTURE,
                ()->call(HttpMethod.DELETE,"/build/deleteNode?id="+STRUCTURE+"&mark=房间",null),
                ()->call(HttpMethod.POST,"/build/addBed",map("roomId",STRUCTURE,"name","bed-new","bedLimit",3)));
        expect(results.get(0),200);
        assertThat(results.get(1).getBody().path("code").asInt()).isIn(200,409);
        assertThat(jdbc.queryForObject("SELECT del_flag FROM room WHERE id=?",String.class,STRUCTURE)).isEqualTo("Y");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bed WHERE room_id=? AND del_flag='N'",Long.class,STRUCTURE)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bed b JOIN room r ON r.id=b.room_id WHERE r.id=? AND r.del_flag='Y' AND b.del_flag='N'",Long.class,STRUCTURE)).isZero();
    }

    @Test void failuresWritingContactsOrContractsRollBackTheWholeAdmissionAndCanBeRetried() {
        expect(call(HttpMethod.POST,"/reserve/addReserve",reservation(FIRST,BED1)),200);
        for(String table:Arrays.asList("emergency_contact","contract")) {
            String before=fingerprint();
            rejectInsert(table);
            try {
                ResponseEntity<JsonNode> response=call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED2));
                assertThat(response.getBody().path("code").asInt()).isEqualTo(500);
                assertThat(fingerprint()).isEqualTo(before);
                assertThat(elderState(FIRST)).isEqualTo("预定"); assertThat(bedState(BED1)).isEqualTo("预定"); assertThat(bedState(BED2)).isEqualTo("空闲");
            } finally { jdbc.execute("DROP TRIGGER bed_test_reject_"+table); }
        }
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED2)),200);
        assertThat(bedState(BED1)).isEqualTo("空闲"); assertThat(bedState(BED2)).isEqualTo("入住");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract WHERE elder_id=?",Long.class,FIRST)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emergency_contact WHERE elder_id=?",Long.class,FIRST)).isEqualTo(1);
    }

    @Test void failedContactOrContractReplacementPreservesTheOriginalAdmissionAndBothBeds() {
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),200);
        for(String table:Arrays.asList("emergency_contact","contract")) {
            String before=fingerprint(); rejectInsert(table);
            try {
                ResponseEntity<JsonNode> response=call(HttpMethod.PUT,"/checkContract/editCheckContract",contract(FIRST,BED2));
                assertThat(response.getBody().path("code").asInt()).isEqualTo(500);
                assertThat(fingerprint()).isEqualTo(before);
                assertThat(bedState(BED1)).isEqualTo("入住"); assertThat(bedState(BED2)).isEqualTo("空闲");
            } finally { jdbc.execute("DROP TRIGGER bed_test_reject_"+table); }
        }
        expect(call(HttpMethod.PUT,"/checkContract/editCheckContract",contract(FIRST,BED2)),200);
        assertThat(bedState(BED1)).isEqualTo("空闲"); assertThat(bedState(BED2)).isEqualTo("入住");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract WHERE elder_id=?",Long.class,FIRST)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emergency_contact WHERE elder_id=?",Long.class,FIRST)).isEqualTo(1);
    }

    @Test void editingStructureCannotReparentExistingNodesThroughForgedParentIds() {
        long other=STRUCTURE+1;
        for(String table:Arrays.asList("building","floor","room"))assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=?",Long.class,other)).isZero();
        jdbc.update("INSERT INTO building(id,name,floor_num,del_flag,create_id,create_time,update_id,update_time) VALUES(?,'other',2,'N',?,NOW(),?,NOW())",other,STAFF,STAFF);
        jdbc.update("INSERT INTO floor(id,building_id,name,room_num,del_flag,create_id,create_time,update_id,update_time) VALUES(?,?,'other',2,'N',?,NOW(),?,NOW())",other,other,STAFF,STAFF);
        jdbc.update("INSERT INTO room(id,type_id,floor_id,name,bed_num,del_flag,create_id,create_time,update_id,update_time) VALUES(?,?,?,'other',3,'N',?,NOW(),?,NOW())",other,STRUCTURE,other,STAFF,STAFF);
        String before=fingerprint();
        expect(call(HttpMethod.PUT,"/build/editFloor",map("id",STRUCTURE,"buildingId",other,"name","changed","roomNum",1,"floorLimit",2)),409);
        expect(call(HttpMethod.PUT,"/build/editRoom",map("id",STRUCTURE,"typeId",STRUCTURE,"floorId",other,"name","changed","bedNum",2,"roomLimit",2)),409);
        expect(call(HttpMethod.PUT,"/build/editBed",map("id",BED1,"roomId",other,"name","changed","bedLimit",3)),409);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test void authorizedIntentionProfileEditsDoNotResetAnAdmittedResidentsStateOrBed() {
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),200);
        expect(call(HttpMethod.PUT,"/intention/editIntention",map("id",FIRST,"name","new-name","idNum",identity(FIRST),"age",71,"sex","女",
                "phone","19900002001","address","changed","bedId",BED2,"checkFlag","意向跟进")),200);
        assertThat(jdbc.queryForObject("SELECT name FROM elder WHERE id=?",String.class,FIRST)).isEqualTo("new-name");
        assertThat(elderState(FIRST)).isEqualTo("入住"); assertThat(bedState(BED1)).isEqualTo("入住"); assertThat(bedState(BED2)).isEqualTo("空闲");
        assertThat(jdbc.queryForObject("SELECT bed_id FROM elder WHERE id=?",Long.class,FIRST)).isEqualTo(BED1);
    }

    private void rejectInsert(String table) {
        assertThat(table).isIn("emergency_contact","contract");
        jdbc.execute("CREATE TRIGGER bed_test_reject_"+table+" BEFORE INSERT ON "+table+" FOR EACH ROW BEGIN IF NEW.elder_id="+FIRST
                +" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed fixture failure'; END IF; END");
    }
    private void assertNoUnrelatedDueReservations() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reserve WHERE state='ACTIVE' AND due_date<CURRENT_DATE() AND elder_id NOT IN (?,?)",Long.class,FIRST,SECOND))
                .as("the isolated expiry fixture must not modify unrelated overdue reservations").isZero();
    }

    private long enterAndApply() {
        expect(call(HttpMethod.POST,"/checkContract/addCheckContract",contract(FIRST,BED1)),200);
        expect(call(HttpMethod.POST,"/retreatApply/addRetreatApply",map("elderId",FIRST)),200);
        return applicationId();
    }
    private long applicationId() { return jdbc.queryForObject("SELECT MAX(id) FROM retreat_apply WHERE elder_id=?",Long.class,FIRST); }
    private long reservationId(long elder) { return jdbc.queryForObject("SELECT MAX(id) FROM reserve WHERE elder_id=?",Long.class,elder); }
    private String reservationState(long id) { return jdbc.queryForObject("SELECT state FROM reserve WHERE id=?",String.class,id); }
    private ResponseEntity<JsonNode> audit(long application,String result) {
        return call(HttpMethod.PUT,"/retreatAudit/auditElderFee",map("applyId",application,"elderId",FIRST,"auditResult",result));
    }

    private void awaitWaiters(String table,long id,int expected) throws Exception {
        long waiting=0,deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) {
            waiting=jdbc.queryForObject("SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w "
                    +"JOIN performance_schema.data_locks b ON b.ENGINE=w.ENGINE AND b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                    +"WHERE b.OBJECT_SCHEMA=DATABASE() AND b.OBJECT_NAME=? AND b.INDEX_NAME='PRIMARY' AND b.LOCK_DATA=?",Long.class,table,String.valueOf(id));
            if(waiting>=expected)break;
            Thread.sleep(25);
        }
        assertThat(waiting).as("requests reached the actual MySQL "+table+" lock barrier").isEqualTo(expected);
    }

    @SafeVarargs private final List<ResponseEntity<JsonNode>> race(String table,long id,Callable<ResponseEntity<JsonNode>>... calls) throws Exception {
        assertThat(table).isIn("elder","bed","building");
        ExecutorService pool=Executors.newFixedThreadPool(calls.length);
        List<Future<ResponseEntity<JsonNode>>> futures=new ArrayList<>();
        try {
            try(Connection blocker=dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                try(PreparedStatement statement=blocker.prepareStatement("SELECT id FROM "+table+" WHERE id=? FOR UPDATE")) {
                    statement.setLong(1,id);
                    try(ResultSet rows=statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                    for(Callable<ResponseEntity<JsonNode>> task:calls) futures.add(pool.submit(task));
                    awaitWaiters(table,id,calls.length);
                } finally { blocker.rollback(); }
            }
            List<ResponseEntity<JsonNode>> result=new ArrayList<>();
            for(Future<ResponseEntity<JsonNode>> future:futures)result.add(future.get(35,TimeUnit.SECONDS));
            return result;
        } finally { pool.shutdown(); assertThat(pool.awaitTermination(40,TimeUnit.SECONDS)).isTrue(); }
    }

    private void assertOneSuccessOneConflict(List<ResponseEntity<JsonNode>> results) {
        List<Integer> codes=new ArrayList<>();
        for(ResponseEntity<JsonNode> response:results) {
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getStatusCodeValue()).isEqualTo(200);
            codes.add(response.getBody().path("code").asInt());
        }
        assertThat(codes).containsExactlyInAnyOrder(200,409);
    }

    private Map<String,Object> reservation(long elder,long bed) {
        return map("bedId",bed,"staffId",STAFF,"payerName","bed-payer","payerPhone","19900002001",
                "dueDate",LocalDate.now().plusDays(30).toString(),"deposit",10,"elderName","bed-elder",
                "idNum",identity(elder),"elderAge",70,"elderSex","女","elderPhone","19900002001","elderAddress","fixture");
    }

    private Map<String,Object> contract(long elder,long bed) {
        return map("id",elder,"bedId",bed,"staffId",STAFF,"name","bed-elder","idNum",identity(elder),"age",70,"sex","女",
                "phone","19900002001","address","fixture","signDate",LocalDate.now().toString(),
                "startDate",LocalDate.now().toString(),"endDate",LocalDate.now().plusYears(1).toString(),
                "operateEmergencyContactQueryList",Collections.singletonList(map("name","bed-person","phone","19900002002",
                        "email","contact@example.invalid","relation","亲属","receiveFlag","N")));
    }

    private String identity(long elder) { return "990101195001"+elder; }
    private String elderState(long elder) { return jdbc.queryForObject("SELECT check_flag FROM elder WHERE id=?",String.class,elder); }
    private String bedState(long bed) { return jdbc.queryForObject("SELECT bed_flag FROM bed WHERE id=?",String.class,bed); }
    private String fingerprint() {
        StringBuilder result=new StringBuilder();
        result.append(jdbc.queryForList("SELECT * FROM elder WHERE create_id=? ORDER BY id",STAFF));
        for(String table:Arrays.asList("bed","room","floor","building"))
            result.append(jdbc.queryForList("SELECT * FROM "+table+" WHERE create_id=? ORDER BY id",STAFF));
        for(String table:Arrays.asList("reserve","contract","emergency_contact","retreat_apply","consult"))
            result.append(jdbc.queryForList("SELECT * FROM "+table+" WHERE elder_id IN (SELECT id FROM elder WHERE create_id=?) ORDER BY id",STAFF));
        return result.toString();
    }
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null)headers.set("token",token);
        return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    private JsonNode expect(ResponseEntity<JsonNode> response,int code) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getStatusCodeValue()).as(response.getBody().toString()).isEqualTo(code==409?200:code);
        assertThat(response.getBody().path("code").asInt()).isEqualTo(code);
        return response.getBody();
    }
    private Map<String,Object> map(Object... values) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);
        return result;
    }
}
