package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real sessions and committed MySQL rows: requests never receive a mocked scope. */
@EnabledIfEnvironmentVariable(named = "SCOPE_HTTP_REDIS_PORT", matches = "[0-9]+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}",
        "spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
        "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}", "spring.redis.password=scope-redis-only",
        "ai.rag.enabled=false", "ai.provider.enabled=false", "security.password-reset.email-enabled=false",
        "filesave.windows=./target/scope-exports", "spring.quartz.auto-startup=false"})
class AdmissionScopeHttpIntegrationTest {
    private static final long ROLE = 861000L;
    private static final long OWN = 861001L;
    private static final long OTHER = 861002L;
    private static final long EMPTY = 861003L;
    private static final long ADMIN = 861004L;
    private static final long FIRST = 861101L;
    private static final long SECOND = 861102L;
    private static final long FOREIGN = 861103L;
    private static final long FOURTH = 861104L;
    private static final long STRUCTURE = 861201L;
    private static final String PASSWORD = "admission-scope-fixture-only";

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private final Set<Long> elders = new LinkedHashSet<>();
    private final List<String> tokens = new ArrayList<>();
    private boolean fixtureCreated;
    private long sourceId;
    private String staffToken;
    private String emptyToken;
    private String adminToken;

    @BeforeEach void createIsolatedStaffWithTheSameModulePermissions() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        http.getRestTemplate().setRequestFactory(factory);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE id IN (?,?,?,?)",
                Long.class, OWN, OTHER, EMPTY, ADMIN)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role WHERE id=?", Long.class, ROLE)).isZero();
        fixtureCreated = true;
        jdbc.update("INSERT INTO role(id,name,create_id,create_time,update_id,update_time) "
                + "VALUES(?,'as-role',1,NOW(),1,NOW())", ROLE);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) "
                + "SELECT ?,id,1,NOW(),1,NOW() FROM auth", ROLE);
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        insertStaff(OWN, ROLE, "as-own", "19900001001", hash);
        insertStaff(OTHER, ROLE, "as-other", "19900001002", hash);
        insertStaff(EMPTY, ROLE, "as-empty", "19900001003", hash);
        insertStaff(ADMIN, 1L, "as-admin", "19900001004", hash);
        sourceId = jdbc.queryForObject("SELECT MIN(id) FROM source", Long.class);
        staffToken = login(OWN);
        emptyToken = login(EMPTY);
        adminToken = login(ADMIN);
    }

    @AfterEach void removeOnlyThisTestsFixtures() {
        if (!fixtureCreated) return;
        for (String token : tokens) call(HttpMethod.DELETE, "/account/logout", token, null);
        elders.addAll(jdbc.queryForList("SELECT id FROM elder WHERE create_id IN (?,?,?,?)",
                Long.class, OWN, OTHER, EMPTY, ADMIN));
        for (Long elder : elders) {
            jdbc.update("DELETE FROM elder_staff_assignment WHERE elder_id=?", elder);
            for (String table : Arrays.asList("consult", "reserve", "visit", "retreat_apply",
                    "visit_plan", "communication_record", "elder_label"))
                jdbc.update("DELETE FROM " + table + " WHERE elder_id=?", elder);
            jdbc.update("DELETE FROM elder WHERE id=?", elder);
        }
        jdbc.update("DELETE FROM bed WHERE room_id=? AND create_id=?", STRUCTURE, OWN);
        jdbc.update("DELETE FROM room WHERE id=? AND create_id=?", STRUCTURE, OWN);
        jdbc.update("DELETE FROM floor WHERE id=? AND create_id=?", STRUCTURE, OWN);
        jdbc.update("DELETE FROM building WHERE id=? AND create_id=?", STRUCTURE, OWN);
        jdbc.update("DELETE FROM ai_audit_log WHERE create_id IN (?,?,?,?)", OWN, OTHER, EMPTY, ADMIN);
        jdbc.update("DELETE FROM role_auth WHERE role_id=?", ROLE);
        jdbc.update("DELETE FROM staff WHERE id IN (?,?,?,?)", OWN, OTHER, EMPTY, ADMIN);
        jdbc.update("DELETE FROM role WHERE id=?", ROLE);
    }

    @Test void consultationsUseStoredAssociationsAndRejectCrossRecordWrites() {
        // FIRST is visible through the sales handler; SECOND through the elder creator.
        seedElder(FIRST, "as-c-own", "咨询中", OTHER, null);
        seedElder(SECOND, "as-c-new", "咨询中", OWN, null);
        seedElder(FOREIGN, "as-c-away", "咨询中", OTHER, null);
        seedConsult(FIRST, FIRST, OWN, OTHER);
        seedConsult(SECOND, SECOND, OTHER, OTHER);
        seedConsult(FOREIGN, FOREIGN, OTHER, OTHER);
        assertPageIds("/consult/pageConsultByKey?elderName=as-c-&scopeStaffId=1", staffToken,
                "elderId", FIRST, SECOND);
        assertPageIds("/consult/pageConsultByKey?elderName=as-c-", emptyToken, "elderId");
        assertPageIds("/consult/pageConsultByKey?elderName=as-c-", adminToken,
                "elderId", FIRST, SECOND, FOREIGN);
        ok(HttpMethod.GET, consultDetail(FIRST, FIRST), staffToken, null);
        ok(HttpMethod.GET, consultDetail(SECOND, SECOND), staffToken, null);

        String before = fingerprint();
        denied(HttpMethod.GET, consultDetail(FOREIGN, FOREIGN), staffToken, null);
        denied(HttpMethod.GET, consultDetail(FOREIGN, FIRST), staffToken, null);
        denied(HttpMethod.GET, consultDetail(FIRST, FOREIGN), staffToken, null);
        denied(HttpMethod.GET, consultDetail(FIRST, FIRST), emptyToken, null);
        denied(HttpMethod.PUT, "/consult/editConsult", staffToken, consultBody(FOREIGN, FOREIGN, OTHER, "as-c-away"));
        denied(HttpMethod.PUT, "/consult/editConsult", staffToken, consultBody(FOREIGN, FIRST, OWN, "as-c-own"));
        denied(HttpMethod.PUT, "/consult/editConsult", staffToken, consultBody(FIRST, FOREIGN, OWN, "as-c-away"));
        denied(HttpMethod.DELETE, "/consult/deleteConsult?elderId=" + FOREIGN, staffToken, null);
        denied(HttpMethod.PUT, "/consult/intentionConsult?elderId=" + FOREIGN, staffToken, null);
        assertThat(fingerprint()).isEqualTo(before);

        ok(HttpMethod.PUT, "/consult/editConsult", staffToken, consultBody(FIRST, FIRST, OWN, "as-c-own"));
        assertThat(jdbc.queryForObject("SELECT consult_content FROM consult WHERE id=?", String.class, FIRST))
                .isEqualTo("scope edited");
        ok(HttpMethod.GET, consultDetail(FOREIGN, FOREIGN), adminToken, null);
        ok(HttpMethod.PUT, "/consult/editConsult", adminToken, consultBody(FOREIGN, FOREIGN, OTHER, "as-c-away"));
        assertThat(jdbc.queryForObject("SELECT consult_content FROM consult WHERE id=?", String.class, FOREIGN))
                .isEqualTo("scope edited");

        Map<String, Object> created = consultBody(null, null, OTHER, "as-c-made");
        created.put("idNum", "990101195001018901");
        created.put("createId", OTHER); // Client metadata must not replace the authenticated creator.
        ok(HttpMethod.POST, "/consult/addConsult", staffToken, created);
        long newElder = jdbc.queryForObject("SELECT id FROM elder WHERE id_num=?", Long.class, created.get("idNum"));
        elders.add(newElder);
        long newConsult = jdbc.queryForObject("SELECT id FROM consult WHERE elder_id=?", Long.class, newElder);
        assertThat(jdbc.queryForObject("SELECT create_id FROM elder WHERE id=?", Long.class, newElder)).isEqualTo(OWN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder_staff_assignment WHERE elder_id=?",
                Long.class, newElder)).isZero();
        ok(HttpMethod.GET, consultDetail(newConsult, newElder), staffToken, null);
        assertPageIds("/consult/pageConsultByKey?elderName=as-c-made", staffToken, "elderId", newElder);
    }

    @Test void reservationsProtectListsRefundsAndExistingIdentityDuringCreation() {
        seedStructure();
        seedBed(FIRST, "预定");
        seedBed(SECOND, "预定");
        seedBed(FOREIGN, "预定");
        seedBed(FOURTH, "空闲");
        seedElder(FIRST, "as-r-own", "预定", OTHER, FIRST);
        seedElder(SECOND, "as-r-new", "预定", OWN, SECOND);
        seedElder(FOREIGN, "as-r-away", "预定", OTHER, FOREIGN);
        seedElder(FOURTH, "as-r-wait", "咨询中", OTHER, null);
        seedReserve(FIRST, FIRST, OWN, OTHER);
        seedReserve(SECOND, SECOND, OTHER, OTHER);
        seedReserve(FOREIGN, FOREIGN, OTHER, OTHER);
        seedConsult(FOURTH, FOURTH, OTHER, OTHER);
        assertPageIds("/reserve/pageReserveByKey?elderName=as-r-&scopeStaffId=1", staffToken,
                "elderId", FIRST, SECOND);
        assertPageIds("/reserve/pageReserveByKey?elderName=as-r-", emptyToken, "elderId");
        assertPageIds("/reserve/pageReserveByKey?elderName=as-r-", adminToken,
                "elderId", FIRST, SECOND, FOREIGN);
        ok(HttpMethod.GET, reserveDetail(FIRST, FIRST), staffToken, null);
        ok(HttpMethod.GET, reserveDetail(SECOND, SECOND), staffToken, null);
        assertPageIds("/reserve/pageSearchElderByKey?name=as-r-wait&scopeStaffId=1", staffToken, "id");
        assertPageIds("/reserve/pageSearchElderByKey?name=as-r-wait", adminToken, "id", FOURTH);

        String before = fingerprint();
        denied(HttpMethod.GET, reserveDetail(FOREIGN, FOREIGN), staffToken, null);
        denied(HttpMethod.GET, reserveDetail(FOREIGN, FIRST), staffToken, null);
        denied(HttpMethod.GET, reserveDetail(FIRST, FOREIGN), staffToken, null);
        denied(HttpMethod.GET, reserveDetail(FIRST, FIRST), emptyToken, null);
        denied(HttpMethod.PUT, "/reserve/refund", staffToken, map("reserveId", FOREIGN, "elderId", FIRST));
        denied(HttpMethod.POST, "/reserve/addReserve", staffToken,
                reserveBody(FOURTH, OWN, idNum(FOURTH), "as-r-stole"));
        assertThat(fingerprint()).isEqualTo(before);

        ok(HttpMethod.PUT, "/reserve/refund", staffToken, map("reserveId", FIRST, "elderId", FIRST));
        assertThat(jdbc.queryForObject("SELECT reserve_flag FROM reserve WHERE id=?", String.class, FIRST)).isEqualTo("Y");
        assertThat(jdbc.queryForObject("SELECT bed_flag FROM bed WHERE id=?", String.class, FIRST)).isEqualTo("空闲");
        ok(HttpMethod.GET, reserveDetail(FOREIGN, FOREIGN), adminToken, null);
        ok(HttpMethod.PUT, "/reserve/refund", adminToken, map("reserveId", FOREIGN, "elderId", FOREIGN));
        assertThat(jdbc.queryForObject("SELECT reserve_flag FROM reserve WHERE id=?", String.class, FOREIGN)).isEqualTo("Y");

        Map<String, Object> created = reserveBody(FOURTH, OTHER, "990101195001018902", "as-r-made");
        created.put("createId", OTHER);
        ok(HttpMethod.POST, "/reserve/addReserve", staffToken, created);
        long newElder = jdbc.queryForObject("SELECT id FROM elder WHERE id_num=?", Long.class, created.get("idNum"));
        elders.add(newElder);
        long newReserve = jdbc.queryForObject("SELECT id FROM reserve WHERE elder_id=?", Long.class, newElder);
        assertThat(jdbc.queryForObject("SELECT create_id FROM elder WHERE id=?", Long.class, newElder)).isEqualTo(OWN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM elder_staff_assignment WHERE elder_id=?",
                Long.class, newElder)).isZero();
        ok(HttpMethod.GET, reserveDetail(newReserve, newElder), staffToken, null);
        assertPageIds("/reserve/pageReserveByKey?elderName=as-r-made", staffToken, "elderId", newElder);
    }

    @Test void historicalSalesAndRefundedReservationsDoNotBypassAssignments() {
        seedStructure();
        seedBed(FIRST, "空闲");
        seedBed(SECOND, "预定");
        seedElder(FIRST, "as-h-old", "咨询中", OWN, null);
        seedElder(SECOND, "as-h-book", "预定", OTHER, SECOND);
        seedConsult(FIRST, FIRST, OWN, OWN);
        seedReserve(FIRST, FIRST, OWN, OWN, FIRST, "CONVERTED");
        seedReserve(SECOND, SECOND, OWN, OTHER);
        assign(FIRST, EMPTY);
        assign(SECOND, EMPTY);
        ok(HttpMethod.GET, consultDetail(FIRST, FIRST), staffToken, null);
        ok(HttpMethod.GET, reserveDetail(SECOND, SECOND), staffToken, null);
        jdbc.update("UPDATE reserve SET reserve_flag='Y',state='REFUNDED' WHERE id=?", SECOND);
        jdbc.update("UPDATE elder SET check_flag='意向跟进',bed_id=NULL WHERE id=?", SECOND);
        jdbc.update("UPDATE bed SET bed_flag='空闲' WHERE id=?", SECOND);
        denied(HttpMethod.GET, reserveDetail(SECOND, SECOND), staffToken, null);
        ok(HttpMethod.GET, reserveDetail(SECOND, SECOND), emptyToken, null);

        // These stages must not inherit access from a creator or an old sales relationship.
        for (String state : Arrays.asList("入住", "退住审核", "已退住", "已失效")) {
            boolean occupied = "入住".equals(state) || "退住审核".equals(state);
            jdbc.update("UPDATE elder SET check_flag=?,bed_id=? WHERE id=?", state, occupied ? FIRST : null, FIRST);
            jdbc.update("UPDATE bed SET bed_flag=? WHERE id=?", occupied ? state : "空闲", FIRST);
            jdbc.update("DELETE FROM retreat_apply WHERE elder_id=?", FIRST);
            if ("退住审核".equals(state))
                jdbc.update("INSERT INTO retreat_apply(elder_id,apply_flag,create_id,create_time,update_id,update_time) VALUES(?,'待审核',?,NOW(),?,NOW())", FIRST, OWN, OWN);
            denied(HttpMethod.GET, consultDetail(FIRST, FIRST), staffToken, null);
            denied(HttpMethod.GET, reserveDetail(FIRST, FIRST), staffToken, null);
            ok(HttpMethod.GET, consultDetail(FIRST, FIRST), emptyToken, null);
            ok(HttpMethod.GET, reserveDetail(FIRST, FIRST), emptyToken, null);
            ok(HttpMethod.GET, consultDetail(FIRST, FIRST), adminToken, null);
            assertPageIds("/reserve/pageReserveByKey?elderName=as-h-old", staffToken, "elderId");
            assertPageIds("/reserve/pageReserveByKey?elderName=as-h-old", emptyToken, "elderId", FIRST);
        }
        jdbc.update("UPDATE elder SET check_flag='意向跟进' WHERE id=?", FIRST);
        ok(HttpMethod.GET, consultDetail(FIRST, FIRST), staffToken, null);
        ok(HttpMethod.GET, reserveDetail(FIRST, FIRST), staffToken, null);
        assertPageIds("/reserve/pageSearchElderByKey?name=as-h-old&scopeStaffId=1", staffToken, "id", FIRST);
        assertPageIds("/reserve/pageSearchElderByKey?name=as-h-old", emptyToken, "id", FIRST);
    }

    @Test void visitsUseTheStoredElderAndRevokeImmediatelyInAnExistingSession() {
        seedStructure();
        seedBed(FIRST, "入住");
        seedBed(FOREIGN, "入住");
        seedElder(FIRST, "as-v-own", "入住", OWN, FIRST);
        seedElder(FOREIGN, "as-v-away", "入住", OWN, FOREIGN);
        seedConsult(FIRST, FIRST, OWN, OWN);
        seedConsult(FOREIGN, FOREIGN, OWN, OWN);
        seedReserve(FIRST, FIRST, OWN, OWN);
        seedReserve(FOREIGN, FOREIGN, OWN, OWN);
        seedVisit(FIRST, FIRST, OWN);
        seedVisit(FOREIGN, FOREIGN, OWN);
        assign(FIRST, OWN);
        assertPageIds("/visit/pageVisitByKey?elderName=as-v-&scopeStaffId=1", staffToken, "id", FIRST);
        assertPageIds("/visit/pageVisitByKey?elderName=as-v-", emptyToken, "id");
        assertPageIds("/visit/pageVisitByKey?elderName=as-v-", adminToken, "id", FIRST, FOREIGN);
        assertPageIds("/visit/pageSearchElderByKey?name=as-v-&scopeStaffId=1", staffToken, "id", FIRST);
        assertPageIds("/visit/pageSearchElderByKey?name=as-v-", emptyToken, "id");
        ok(HttpMethod.GET, "/visit/getVisitById?visitId=" + FIRST, staffToken, null);

        String before = fingerprint();
        denied(HttpMethod.GET, "/visit/getVisitById?visitId=" + FOREIGN, staffToken, null);
        denied(HttpMethod.GET, "/visit/getVisitById?visitId=" + FIRST, emptyToken, null);
        denied(HttpMethod.POST, "/visit/addVisit", staffToken, visitBody(null, FOREIGN, "as-denied"));
        denied(HttpMethod.PUT, "/visit/editVisit", staffToken, visitBody(FOREIGN, FIRST, "as-denied"));
        denied(HttpMethod.PUT, "/visit/recordLeave", staffToken,
                map("id", FOREIGN, "elderId", FIRST, "leaveDate", LocalDate.now().toString()));
        denied(HttpMethod.DELETE, "/visit/deleteVisit?visitId=" + FOREIGN, staffToken, null);
        assertThat(fingerprint()).isEqualTo(before);

        // A supplied primary key on create cannot overwrite another elder's visit.
        ok(HttpMethod.POST, "/visit/addVisit", staffToken, visitBody(FOREIGN, FIRST, "as-created"));
        long created = jdbc.queryForObject("SELECT id FROM visit WHERE elder_id=? AND name='as-created'",
                Long.class, FIRST);
        assertThat(created).isNotEqualTo(FOREIGN);
        assertThat(jdbc.queryForObject("SELECT elder_id FROM visit WHERE id=?", Long.class, FOREIGN)).isEqualTo(FOREIGN);
        ok(HttpMethod.GET, "/visit/getVisitById?visitId=" + created, staffToken, null);
        ok(HttpMethod.PUT, "/visit/editVisit", staffToken, visitBody(FIRST, FOREIGN, "as-edited"));
        assertThat(jdbc.queryForObject("SELECT elder_id FROM visit WHERE id=?", Long.class, FIRST)).isEqualTo(FIRST);
        ok(HttpMethod.PUT, "/visit/recordLeave", staffToken,
                map("id", FIRST, "leaveDate", LocalDate.now().toString()));
        ok(HttpMethod.DELETE, "/visit/deleteVisit?visitId=" + FIRST, staffToken, null);
        assertThat(jdbc.queryForObject("SELECT del_flag FROM visit WHERE id=?", String.class, FIRST)).isEqualTo("Y");

        ok(HttpMethod.DELETE, "/ai/scope/assignment?elderId=" + FIRST + "&staffId=" + OWN, adminToken, null);
        assertThat(jdbc.queryForObject("SELECT active FROM elder_staff_assignment WHERE elder_id=? AND staff_id=?",
                String.class, FIRST, OWN)).isEqualTo("N");
        String afterRevoke = fingerprint();
        denied(HttpMethod.GET, "/visit/getVisitById?visitId=" + created, staffToken, null);
        denied(HttpMethod.POST, "/visit/addVisit", staffToken, visitBody(null, FIRST, "as-revoke"));
        denied(HttpMethod.PUT, "/visit/editVisit", staffToken, visitBody(created, FIRST, "as-revoke"));
        denied(HttpMethod.PUT, "/visit/recordLeave", staffToken,
                map("id", created, "leaveDate", LocalDate.now().toString()));
        denied(HttpMethod.DELETE, "/visit/deleteVisit?visitId=" + created, staffToken, null);
        denied(HttpMethod.GET, consultDetail(FIRST, FIRST), staffToken, null);
        denied(HttpMethod.GET, reserveDetail(FIRST, FIRST), staffToken, null);
        assertPageIds("/visit/pageVisitByKey?elderName=as-v-", staffToken, "id");
        assertPageIds("/visit/pageSearchElderByKey?name=as-v-", staffToken, "id");
        assertPageIds("/reserve/pageReserveByKey?elderName=as-v-", staffToken, "elderId");
        assertThat(fingerprint()).isEqualTo(afterRevoke);

        ok(HttpMethod.GET, "/visit/getVisitById?visitId=" + FOREIGN, adminToken, null);
        ok(HttpMethod.PUT, "/visit/editVisit", adminToken, visitBody(FOREIGN, FOREIGN, "as-admin"));
        ok(HttpMethod.PUT, "/visit/recordLeave", adminToken,
                map("id", FOREIGN, "leaveDate", LocalDate.now().toString()));
        ok(HttpMethod.DELETE, "/visit/deleteVisit?visitId=" + FOREIGN, adminToken, null);
        assertThat(jdbc.queryForObject("SELECT del_flag FROM visit WHERE id=?", String.class, FOREIGN)).isEqualTo("Y");
    }

    @Test void bedPanoramaPreservesInventoryWithoutRevealingUnassignedOccupants() {
        seedStructure();
        seedBed(FIRST, "入住");
        seedBed(SECOND, "空闲");
        seedBed(FOREIGN, "退住审核");
        seedElder(FIRST, "as-b-own", "入住", OTHER, FIRST);
        seedElder(FOREIGN, "as-b-away", "退住审核", OTHER, FOREIGN);
        assign(FIRST, OWN);
        String path = "/bedPanorama/listRoomByKey?buildingId=" + STRUCTURE;
        Map<Long, JsonNode> staffBeds = panoramaBeds(ok(HttpMethod.GET, path + "&scopeStaffId=1", staffToken, null));
        Map<Long, JsonNode> emptyBeds = panoramaBeds(ok(HttpMethod.GET, path, emptyToken, null));
        Map<Long, JsonNode> adminBeds = panoramaBeds(ok(HttpMethod.GET, path, adminToken, null));
        for (Map<Long, JsonNode> inventory : Arrays.asList(staffBeds, emptyBeds, adminBeds)) {
            assertThat(inventory.keySet()).containsExactlyInAnyOrder(FIRST, SECOND, FOREIGN);
            assertThat(inventory.get(FIRST).path("bedFlag").asText()).isEqualTo("入住");
            assertThat(inventory.get(SECOND).path("bedFlag").asText()).isEqualTo("空闲");
            assertThat(inventory.get(FOREIGN).path("bedFlag").asText()).isEqualTo("退住审核");
        }
        assertThat(staffBeds.get(FIRST).path("elderName").asText()).isEqualTo("as-b-own");
        assertThat(staffBeds.get(FIRST).path("sex").asText()).isEqualTo("女");
        assertThat(staffBeds.get(FIRST).path("age").asInt()).isEqualTo(70);
        assertHiddenOccupant(staffBeds.get(FOREIGN));
        assertHiddenOccupant(emptyBeds.get(FIRST));
        assertHiddenOccupant(emptyBeds.get(FOREIGN));
        assertThat(adminBeds.get(FOREIGN).path("elderName").asText()).isEqualTo("as-b-away");
        assertThat(panoramaBeds(ok(HttpMethod.GET, path + "&elderName=as-b-away&scopeStaffId=1", staffToken, null))).isEmpty();
        assertThat(panoramaBeds(ok(HttpMethod.GET, path + "&elderName=as-b-own", emptyToken, null))).isEmpty();
        assertThat(panoramaBeds(ok(HttpMethod.GET, path + "&elderName=as-b-own", staffToken, null)).keySet())
                .containsExactly(FIRST);
        assertThat(panoramaBeds(ok(HttpMethod.GET, path + "&elderName=as-b-away", adminToken, null)).keySet())
                .containsExactly(FOREIGN);
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE elder_id=? AND staff_id=?", FIRST, OWN);
        Map<Long, JsonNode> revoked = panoramaBeds(ok(HttpMethod.GET, path, staffToken, null));
        assertThat(revoked.keySet()).containsExactlyInAnyOrder(FIRST, SECOND, FOREIGN);
        assertHiddenOccupant(revoked.get(FIRST));
        assertThat(panoramaBeds(ok(HttpMethod.GET, path + "&elderName=as-b-own", staffToken, null))).isEmpty();
    }

    @Test void retreatAuditBindsTheApplicationToItsStoredElderBeforeAnyWrite() {
        seedStructure();
        seedBed(FIRST, "退住审核");
        seedBed(FOREIGN, "退住审核");
        seedElder(FIRST, "as-a-own", "退住审核", OTHER, FIRST);
        seedElder(FOREIGN, "as-a-away", "退住审核", OTHER, FOREIGN);
        for (long elder : Arrays.asList(FIRST, FOREIGN))
            jdbc.update("INSERT INTO retreat_apply(id,elder_id,apply_flag,create_id,create_time,update_id,update_time) "
                    + "VALUES(?,?,'待审核',?,NOW(),?,NOW())", elder, elder, OTHER, OTHER);
        assign(FIRST, OWN);
        String before = fingerprint();
        denied(HttpMethod.PUT, "/retreatAudit/auditElderFee", staffToken,
                map("applyId", FOREIGN, "elderId", FIRST, "auditResult", "不通过"));
        denied(HttpMethod.PUT, "/retreatAudit/auditElderFee", staffToken,
                map("applyId", FIRST, "elderId", FOREIGN, "auditResult", "不通过"));
        denied(HttpMethod.PUT, "/retreatAudit/auditElderFee", adminToken,
                map("applyId", FOREIGN, "elderId", FIRST, "auditResult", "不通过"));
        assertThat(fingerprint()).isEqualTo(before);
        ok(HttpMethod.PUT, "/retreatAudit/auditElderFee", staffToken,
                map("applyId", FIRST, "elderId", FIRST, "auditResult", "不通过"));
        ok(HttpMethod.PUT, "/retreatAudit/auditElderFee", adminToken,
                map("applyId", FOREIGN, "elderId", FOREIGN, "auditResult", "不通过"));
        for (long elder : Arrays.asList(FIRST, FOREIGN)) {
            assertThat(jdbc.queryForObject("SELECT check_flag FROM elder WHERE id=?", String.class, elder)).isEqualTo("入住");
            assertThat(jdbc.queryForObject("SELECT apply_flag FROM retreat_apply WHERE id=?", String.class, elder)).isEqualTo("未通过");
            assertThat(jdbc.queryForObject("SELECT bed_flag FROM bed WHERE id=?", String.class, elder)).isEqualTo("入住");
        }
    }

    @Test void intentionChildrenUseStoredOwnershipAndFollowStageAndAssignmentChanges() {
        seedStructure();
        seedBed(FIRST, "空闲");
        seedElder(FIRST, "as-i-own", "意向跟进", OWN, null);
        seedElder(FOREIGN, "as-i-away", "意向跟进", OTHER, null);
        for (long elder : Arrays.asList(FIRST, FOREIGN)) {
            jdbc.update("INSERT INTO visit_plan(id,elder_id,title,plan_date,del_flag,create_id,create_time,update_id,update_time) "
                    + "VALUES(?,?,'as-plan',NOW(),'N',?,NOW(),?,NOW())", elder, elder, OTHER, OTHER);
            jdbc.update("INSERT INTO communication_record(id,elder_id,record_date,communication_record,del_flag,"
                    + "create_id,create_time,update_id,update_time) VALUES(?,?,NOW(),'as-record','N',?,NOW(),?,NOW())",
                    elder, elder, OTHER, OTHER);
        }
        String today = LocalDate.now().toString();
        assertPageIds("/intention/pageVisitPlan?completeFlag=false&elderId=" + FIRST, staffToken, "id", FIRST);
        assertPageIds("/intention/pageCommunicationRecord?elderId=" + FIRST, staffToken, "id", FIRST);
        ok(HttpMethod.GET, "/intention/getElderLabelById?elderId=" + FIRST, staffToken, null);
        ok(HttpMethod.GET, "/intention/getEditElderLabelById?elderId=" + FIRST, staffToken, null);
        String before = fingerprint();
        for (String path : Arrays.asList("getElderLabelById", "getEditElderLabelById",
                "pageVisitPlan?completeFlag=false&", "pageCommunicationRecord?pageNum=1&pageSize=100&")) {
            String separator = path.contains("?") ? "" : "?";
            denied(HttpMethod.GET, "/intention/" + path + separator + "elderId=" + FOREIGN, staffToken, null);
        }
        denied(HttpMethod.PUT, "/intention/editElderLabel", staffToken,
                map("elderId", FOREIGN, "labelIdList", new ArrayList<>()));
        denied(HttpMethod.POST, "/intention/addVisitPlan", staffToken,
                map("elderId", FOREIGN, "title", "as-forbidden", "planDate", today));
        denied(HttpMethod.PUT, "/intention/executeVisitPlan", staffToken,
                map("id", FOREIGN, "elderId", FIRST, "content", "forbidden", "completeDate", today));
        denied(HttpMethod.DELETE, "/intention/deleteVisitPlan?visitPlanId=" + FOREIGN, staffToken, null);
        denied(HttpMethod.POST, "/intention/addCommunicationRecord", staffToken,
                map("elderId", FOREIGN, "recordDate", today, "communicationRecord", "forbidden"));
        denied(HttpMethod.PUT, "/intention/editCommunicationRecord", staffToken,
                map("id", FOREIGN, "elderId", FIRST, "recordDate", today, "communicationRecord", "forbidden"));
        denied(HttpMethod.DELETE, "/intention/deleteCommunicationRecord?communicationRecordId=" + FOREIGN, staffToken, null);
        assertThat(fingerprint()).isEqualTo(before);

        ok(HttpMethod.PUT, "/intention/editElderLabel", staffToken,
                map("elderId", FIRST, "labelIdList", new ArrayList<>()));
        ok(HttpMethod.PUT, "/intention/executeVisitPlan", staffToken,
                map("id", FIRST, "elderId", FOREIGN, "delFlag", "Y", "content", "as-done", "completeDate", today));
        ok(HttpMethod.PUT, "/intention/editCommunicationRecord", staffToken,
                map("id", FIRST, "elderId", FOREIGN, "delFlag", "Y", "recordDate", today, "communicationRecord", "as-edited"));
        for (String table : Arrays.asList("visit_plan", "communication_record")) {
            assertThat(jdbc.queryForObject("SELECT elder_id FROM " + table + " WHERE id=?", Long.class, FIRST)).isEqualTo(FIRST);
            assertThat(jdbc.queryForObject("SELECT del_flag FROM " + table + " WHERE id=?", String.class, FIRST)).isEqualTo("N");
        }
        assertThat(jdbc.queryForObject("SELECT content FROM visit_plan WHERE id=?", String.class, FIRST)).isEqualTo("as-done");
        assertThat(jdbc.queryForObject("SELECT communication_record FROM communication_record WHERE id=?", String.class, FIRST))
                .isEqualTo("as-edited");
        ok(HttpMethod.POST, "/intention/addVisitPlan", staffToken,
                map("elderId", FIRST, "title", "as-new", "planDate", today));
        ok(HttpMethod.POST, "/intention/addCommunicationRecord", staffToken,
                map("elderId", FIRST, "recordDate", today, "communicationRecord", "as-new"));

        jdbc.update("UPDATE elder SET check_flag='入住',bed_id=? WHERE id=?", FIRST, FIRST);
        jdbc.update("UPDATE bed SET bed_flag='入住' WHERE id=?", FIRST);
        before = fingerprint();
        denied(HttpMethod.GET, "/intention/getElderLabelById?elderId=" + FIRST, staffToken, null);
        denied(HttpMethod.DELETE, "/intention/deleteVisitPlan?visitPlanId=" + FIRST, staffToken, null);
        assertThat(fingerprint()).isEqualTo(before);
        assign(FIRST, OWN);
        ok(HttpMethod.GET, "/intention/getElderLabelById?elderId=" + FIRST, staffToken, null);
        jdbc.update("UPDATE elder_staff_assignment SET active='N' WHERE elder_id=? AND staff_id=?", FIRST, OWN);
        denied(HttpMethod.GET, "/intention/pageCommunicationRecord?elderId=" + FIRST, staffToken, null);
        ok(HttpMethod.GET, "/intention/getElderLabelById?elderId=" + FOREIGN, adminToken, null);
        ok(HttpMethod.DELETE, "/intention/deleteCommunicationRecord?communicationRecordId=" + FOREIGN, adminToken, null);
        assertThat(jdbc.queryForObject("SELECT del_flag FROM communication_record WHERE id=?", String.class, FOREIGN)).isEqualTo("Y");
    }

    private void insertStaff(long id, long role, String name, String phone, String hash) {
        jdbc.update("INSERT INTO staff(id,role_id,name,id_num,age,sex,phone,email,pass,avator,address,leave_flag,"
                        + "create_id,create_time,update_id,update_time) VALUES(?,?,?, ?,30,'男',?,'scope@example.invalid',"
                        + "?,'scope-avatar','scope fixture','N',1,NOW(),1,NOW())",
                id, role, name, "990101199001" + id, phone, hash);
    }

    private void seedElder(long id, String name, String state, long creator, Long bed) {
        jdbc.update("INSERT INTO elder(id,bed_id,name,id_num,age,sex,phone,address,balance,check_flag,"
                        + "create_id,create_time,update_id,update_time) VALUES(?,?,?,?,70,'女','19900002001',"
                        + "'scope fixture',100,?,?,NOW(),?,NOW())",
                id, bed, name, idNum(id), state, creator, creator);
        elders.add(id);
    }

    private void seedConsult(long id, long elder, long handler, long creator) {
        jdbc.update("INSERT INTO consult(id,elder_id,source_id,staff_id,name,phone,relation,consult_date,"
                        + "consult_content,create_id,create_time,update_id,update_time) "
                        + "VALUES(?,?,?,?,'as-contact','19900002001','亲属',NOW(),'scope seed',?,NOW(),?,NOW())",
                id, elder, sourceId, handler, creator, creator);
    }

    private void seedReserve(long id, long elder, long handler, long creator) {
        Long bed = jdbc.queryForObject("SELECT bed_id FROM elder WHERE id=?", Long.class, elder);
        String elderState = jdbc.queryForObject("SELECT check_flag FROM elder WHERE id=?", String.class, elder);
        assertThat(bed).as("reservation fixture has a real immutable bed snapshot").isNotNull();
        seedReserve(id, elder, handler, creator, bed, "预定".equals(elderState) ? "ACTIVE" : "CONVERTED");
    }

    private void seedReserve(long id, long elder, long handler, long creator, long bed, String state) {
        jdbc.update("INSERT INTO reserve(id,elder_id,staff_id,bed_id,state,name,phone,due_date,deposit,reserve_flag,"
                        + "create_id,create_time,update_id,update_time) "
                        + "VALUES(?,?,?,?,?,'as-payer','19900002001',DATE_ADD(NOW(),INTERVAL 30 DAY),10,'N',?,NOW(),?,NOW())",
                id, elder, handler, bed, state, creator, creator);
    }

    private void seedVisit(long id, long elder, long creator) {
        jdbc.update("INSERT INTO visit(id,elder_id,name,phone,relation,visit_date,visit_num,visit_flag,del_flag,"
                        + "create_id,create_time,update_id,update_time) "
                        + "VALUES(?,?,'as-visitor','19900002001','亲属',NOW(),1,'待离开','N',?,NOW(),?,NOW())",
                id, elder, creator, creator);
    }

    private void assign(long elder, long staff) {
        jdbc.update("INSERT INTO elder_staff_assignment(elder_id,staff_id,active,create_id,update_id) VALUES(?,?,'Y',?,?)",
                elder, staff, ADMIN, ADMIN);
    }

    private void seedStructure() {
        long roomType = jdbc.queryForObject("SELECT MIN(id) FROM room_type", Long.class);
        jdbc.update("INSERT INTO building(id,name,floor_num,del_flag,create_id,create_time,update_id,update_time) "
                + "VALUES(?,'as-build',1,'N',?,NOW(),?,NOW())", STRUCTURE, OWN, OWN);
        jdbc.update("INSERT INTO floor(id,building_id,name,room_num,del_flag,create_id,create_time,update_id,update_time) "
                + "VALUES(?,?,'as-floor',1,'N',?,NOW(),?,NOW())", STRUCTURE, STRUCTURE, OWN, OWN);
        jdbc.update("INSERT INTO room(id,type_id,floor_id,name,bed_num,del_flag,create_id,create_time,update_id,update_time) "
                + "VALUES(?,?,?,'as-room',4,'N',?,NOW(),?,NOW())", STRUCTURE, roomType, STRUCTURE, OWN, OWN);
    }

    private void seedBed(long id, String state) {
        jdbc.update("INSERT INTO bed(id,room_id,name,bed_flag,del_flag,create_id,create_time,update_id,update_time) "
                + "VALUES(?,?,'as-bed',?,'N',?,NOW(),?,NOW())", id, STRUCTURE, state, OWN, OWN);
    }

    private Map<String, Object> consultBody(Long consult, Long elder, long handler, String name) {
        return map("consultId", consult, "elderId", elder, "sourceId", sourceId, "staffId", handler,
                "consultName", "as-contact", "consultPhone", "19900002001", "relation", "亲属",
                "consultDate", LocalDate.now().toString(), "consultContent", "scope edited", "elderName", name,
                "idNum", elder == null ? "990101195001018901" : idNum(elder), "age", "70", "sex", "女",
                "elderPhone", "19900002001", "address", "scope fixture");
    }

    private Map<String, Object> reserveBody(long bed, long handler, String identity, String name) {
        return map("bedId", bed, "staffId", handler, "payerName", "as-payer", "payerPhone", "19900002001",
                "dueDate", LocalDate.now().plusDays(30).toString(), "deposit", 10, "elderName", name,
                "idNum", identity, "elderAge", 70, "elderSex", "女", "elderPhone", "19900002001",
                "elderAddress", "scope fixture");
    }

    private Map<String, Object> visitBody(Long visit, long elder, String name) {
        return map("id", visit, "elderId", elder, "name", name, "phone", "19900002001", "relation", "亲属",
                "visitDate", LocalDate.now().toString(), "visitNum", 1);
    }

    private Map<Long, JsonNode> panoramaBeds(JsonNode rooms) {
        assertThat(rooms.isArray()).isTrue();
        Map<Long, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode room : rooms)
            for (JsonNode bed : room.path("bedList")) result.put(bed.path("id").asLong(), bed);
        return result;
    }

    private void assertHiddenOccupant(JsonNode bed) {
        assertThat(bed).isNotNull();
        for (String field : Arrays.asList("elderName", "sex", "age"))
            assertThat(bed.path(field).isNull() || bed.path(field).isMissingNode())
                    .as("unassigned bed must not expose " + field).isTrue();
    }

    private String idNum(long id) { return "990101195001" + id; }

    private String consultDetail(long consult, long elder) {
        return "/consult/getConsultByConsultIdAndElderId?consultId=" + consult + "&elderId=" + elder;
    }

    private String reserveDetail(long reserve, long elder) {
        return "/reserve/getReserveByReserveIdAndElderId?reserveId=" + reserve + "&elderId=" + elder;
    }

    private String login(long id) {
        String phone = jdbc.queryForObject("SELECT phone FROM staff WHERE id=?", String.class, id);
        String token = ok(HttpMethod.POST, "/account/login", null, map("phone", phone, "pass", PASSWORD))
                .path("token").asText();
        assertThat(token).isNotBlank();
        tokens.add(token);
        return token;
    }

    private void assertPageIds(String path, String token, String field, Long... expected) {
        JsonNode page = ok(HttpMethod.GET, path + "&pageNum=1&pageSize=100", token, null);
        assertThat(page.path("total").asLong()).as(path).isEqualTo(expected.length);
        List<Long> actual = new ArrayList<>();
        for (JsonNode row : page.path("list")) actual.add(row.path(field).asLong());
        assertThat(actual).as(path).containsExactlyInAnyOrder(expected);
    }

    private JsonNode ok(HttpMethod method, String path, String token, Object body) {
        return assertCode(call(method, path, token, body), 200, path).path("data");
    }

    private void denied(HttpMethod method, String path, String token, Object body) {
        assertCode(call(method, path, token, body), 403, path);
    }

    private JsonNode assertCode(ResponseEntity<JsonNode> response, int expected, String path) {
        assertThat(response.getStatusCodeValue()).as(path + " HTTP status").isEqualTo(expected);
        assertThat(response.getBody()).as(path + " JSON response").isNotNull();
        assertThat(response.getBody().path("code").asInt()).as(path + " response code").isEqualTo(expected);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set("token", token);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private String fingerprint() {
        StringBuilder result = new StringBuilder();
        for (Long elder : elders) {
            result.append(jdbc.queryForList("SELECT * FROM elder WHERE id=?", elder));
            for (String table : Arrays.asList("consult", "reserve", "visit", "retreat_apply",
                    "visit_plan", "communication_record", "elder_label"))
                result.append(jdbc.queryForList("SELECT * FROM " + table + " WHERE elder_id=? ORDER BY id", elder));
        }
        result.append(jdbc.queryForList("SELECT * FROM bed WHERE room_id=? ORDER BY id", STRUCTURE));
        return result.toString();
    }
}
