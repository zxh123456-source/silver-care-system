package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shanzhu.beadhouse.entity.po.PolicyRagSync;
import com.shanzhu.beadhouse.service.common.PolicyRagClient;
import com.shanzhu.beadhouse.service.common.PolicyRagSyncService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.UriComponentsBuilder;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP, MySQL row-lock barriers and a local, deterministic RAG HTTP peer. */
@EnabledIfEnvironmentVariable(named="SCOPE_TEST_DB_URL", matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}","spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
        "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}","spring.redis.password=scope-redis-only",
        "ai.rag.enabled=false","ai.rag.retry.enabled=false","ai.alert.scan.enabled=false",
        "ai.provider.enabled=false","security.password-reset.email-enabled=false","spring.quartz.auto-startup=false"})
class PolicyRagSyncHttpIntegrationTest {
    private static final long ROLE=869000, STAFF=869001;
    private static final String TITLE="RagLeaseFixture869-Case", SOURCE="rag-fixture-869.md";
    private static final String PASSWORD="rag-fixture-test-only";
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    @Autowired PolicyRagSyncService worker;
    @Autowired PolicyRagClient client;
    @Autowired ObjectMapper mapper;
    private String token, documentKey;
    private HttpServer peer;
    private ExecutorService peerExecutor;
    private final List<JsonNode> received=new CopyOnWriteArrayList<>();
    private final List<String> paths=new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> peerFailure=new AtomicReference<>();
    private CountDownLatch entered, release;
    private volatile String reply="ECHO";
    private boolean created;
    private Object savedUrl, savedEnabled, savedReadTimeout;

    @BeforeEach void seedOnlyOwnedRows() throws Exception {
        assertThat(number("SELECT COUNT(*) FROM role WHERE id=?",ROLE)).isZero();
        assertThat(number("SELECT COUNT(*) FROM staff WHERE id=?",STAFF)).isZero();
        assertThat(number("SELECT COUNT(*) FROM policy_doc WHERE source=?",SOURCE)).isZero();
        assertThat(number("SELECT COUNT(*) FROM policy_rag_sync WHERE source=?",SOURCE)).isZero();
        created=true;
        jdbc.update("INSERT INTO role(id,name,create_id,create_time,update_id,update_time) VALUES(?,'rag-role',1,NOW(),1,NOW())",ROLE);
        jdbc.update("INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time) "
                +"SELECT ?,id,1,NOW(),1,NOW() FROM auth WHERE url='/ai/policy/index'",ROLE);
        assertThat(number("SELECT COUNT(*) FROM role_auth WHERE role_id=?",ROLE)).isEqualTo(1);
        jdbc.update("INSERT INTO staff(id,role_id,name,id_num,age,sex,phone,email,pass,avator,address,leave_flag,"
                        +"create_id,create_time,update_id,update_time) VALUES(?,?,'rag-staff',?,30,'女',?,'rag@example.invalid',?,'','fixture','N',1,NOW(),1,NOW())",
                STAFF,ROLE,"990101199001"+STAFF,"19900"+STAFF,new BCryptPasswordEncoder().encode(PASSWORD));
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false); factory.setConnectTimeout(10000); factory.setReadTimeout(40000);
        http.getRestTemplate().setRequestFactory(factory);
        token=post("/account/login",map("phone","19900"+STAFF,"pass",PASSWORD),false).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        savedUrl=ReflectionTestUtils.getField(client,"baseUrl");
        savedEnabled=ReflectionTestUtils.getField(client,"enabled");
        savedReadTimeout=ReflectionTestUtils.getField(client,"readTimeoutMs");
        ReflectionTestUtils.setField(client,"enabled",false);
        peer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        peerExecutor=Executors.newCachedThreadPool();
        peer.setExecutor(peerExecutor); peer.createContext("/",this::respond); peer.start();
        ReflectionTestUtils.setField(client,"baseUrl","http://127.0.0.1:"+peer.getAddress().getPort());
        ReflectionTestUtils.setField(client,"readTimeoutMs",10000);
        documentKey=worker.documentKey(TITLE,SOURCE);
    }

    @AfterEach void cleanupOnlyOwnedRows() throws Exception {
        if(release!=null) release.countDown();
        if(peer!=null) peer.stop(0);
        if(peerExecutor!=null) { peerExecutor.shutdownNow(); peerExecutor.awaitTermination(10,TimeUnit.SECONDS); }
        if(savedUrl!=null) ReflectionTestUtils.setField(client,"baseUrl",savedUrl);
        if(savedEnabled!=null) ReflectionTestUtils.setField(client,"enabled",savedEnabled);
        if(savedReadTimeout!=null) ReflectionTestUtils.setField(client,"readTimeoutMs",savedReadTimeout);
        if(!created) return;
        jdbc.execute("DROP TRIGGER IF EXISTS rag_owned_source_failure");
        jdbc.execute("DROP TRIGGER IF EXISTS rag_owned_sync_failure");
        jdbc.update("DELETE FROM ai_audit_log WHERE object_type='policy_rag_sync' AND object_id IN "
                +"(SELECT id FROM policy_rag_sync WHERE source=?)",SOURCE);
        jdbc.update("DELETE FROM policy_doc WHERE source=?",SOURCE);
        jdbc.update("DELETE FROM policy_rag_sync WHERE source=?",SOURCE);
        if(token!=null) request(HttpMethod.DELETE,"/account/logout",null,true);
        jdbc.update("DELETE FROM ai_audit_log WHERE operator_id=?",STAFF);
        jdbc.update("DELETE FROM role_auth WHERE role_id=?",ROLE);
        jdbc.update("DELETE FROM staff WHERE id=? AND role_id=?",STAFF,ROLE);
        jdbc.update("DELETE FROM role WHERE id=?",ROLE);
        assertThat(peerFailure.get()).as("local HTTP peer must finish its asserted protocol").isNull();
    }

    @Test void disabledRagStillPersistsMonotonicDesiredSnapshotsAndTrimmedDelete() throws Exception {
        imported(TITLE,"第一版记录。\n\n第二段。 ");
        Map<String,Object> first=state();
        assertThat(((Number)first.get("desired_version")).longValue()).isEqualTo(1);
        assertThat(first.get("status")).isEqualTo("PENDING");
        JsonNode snapshot=mapper.readTree((String)first.get("payload_json"));
        assertThat(snapshot.path("chunks").size()).isEqualTo(2);
        assertCanonicalSnapshot(snapshot);
        assertThat(received).isEmpty();
        ok(delete("  "+TITLE+"  "," "+SOURCE+" "));
        Map<String,Object> deleted=state();
        assertThat(((Number)deleted.get("desired_version")).longValue()).isEqualTo(2);
        assertThat(deleted.get("desired_action")).isEqualTo("DELETE");
        assertThat(number("SELECT COUNT(*) FROM policy_doc WHERE source=?",SOURCE)).isZero();
        JsonNode tombstone=mapper.readTree((String)deleted.get("payload_json"));
        assertThat(tombstone.path("chunks").size()).isZero();
        assertCanonicalSnapshot(tombstone);
        assertThat(received).isEmpty();
    }

    @Test void enabledImportNeverSendsNetworkFromItsSourceTransaction() {
        ReflectionTestUtils.setField(client,"enabled",true);
        imported(TITLE,"网络由领取者发出。");
        assertThat(received).as("source import queues durable work without afterCommit HTTP").isEmpty();
        assertThat(state().get("status")).isEqualTo("PENDING");
    }

    @Test void caseSensitiveIdentityDoesNotDeleteOrMixDifferentDocumentKeys() throws Exception {
        imported(TITLE,"大写标题原文。");
        imported(TITLE.toLowerCase(Locale.ROOT),"小写标题原文。");
        assertThat(number("SELECT COUNT(*) FROM policy_rag_sync WHERE source=?",SOURCE)).isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM policy_doc WHERE source=?",SOURCE)).isEqualTo(2);
        ok(delete(" "+TITLE+" ",SOURCE));
        assertThat(jdbc.queryForList("SELECT content FROM policy_doc WHERE source=?",String.class,SOURCE))
                .containsExactly("小写标题原文。");
        String lowerKey=worker.documentKey(TITLE.toLowerCase(Locale.ROOT),SOURCE);
        assertThat(lowerKey).isNotEqualTo(documentKey);
        assertThat(row(lowerKey).get("desired_action")).isEqualTo("UPSERT");
        JsonNode lower=mapper.readTree((String)row(lowerKey).get("payload_json"));
        assertThat(lower.path("chunks").get(0).path("content").asText()).isEqualTo("小写标题原文。");
    }

    @Test void failedSourceInsertRollsBackReplacementAndVersion() throws Exception {
        imported(TITLE,"保留原制度。");
        Map<String,Object> before=state();
        List<Map<String,Object>> sourceBefore=jdbc.queryForList("SELECT * FROM policy_doc WHERE source=?",SOURCE);
        jdbc.execute("CREATE TRIGGER rag_owned_source_failure BEFORE INSERT ON policy_doc FOR EACH ROW "
                +"BEGIN IF NEW.source='"+SOURCE+"' AND NEW.content LIKE '%ROLLBACK869%' THEN SIGNAL SQLSTATE '45000' "
                +"SET MESSAGE_TEXT='synthetic source insert failure'; END IF; END");
        JsonNode failed=post("/ai/policy/import",map("title",TITLE,"source",SOURCE,"content","ROLLBACK869"),true);
        assertThat(failed.path("code").asInt()).isNotEqualTo(200);
        assertThat(state().get("desired_version")).isEqualTo(before.get("desired_version"));
        assertThat(state().get("payload_json")).isEqualTo(before.get("payload_json"));
        assertThat(jdbc.queryForList("SELECT * FROM policy_doc WHERE source=?",SOURCE)).isEqualTo(sourceBefore);
    }

    @Test void failedSnapshotWriteRollsBackAllNewSourceChunksAndVersion() {
        imported(TITLE,"原始不可丢。");
        Map<String,Object> before=state();
        List<Map<String,Object>> sourceBefore=jdbc.queryForList("SELECT * FROM policy_doc WHERE source=?",SOURCE);
        jdbc.execute("CREATE TRIGGER rag_owned_sync_failure BEFORE UPDATE ON policy_rag_sync FOR EACH ROW "
                +"BEGIN IF NEW.source='"+SOURCE+"' AND NEW.desired_version>OLD.desired_version THEN SIGNAL SQLSTATE '45000' "
                +"SET MESSAGE_TEXT='synthetic snapshot failure'; END IF; END");
        JsonNode failed=post("/ai/policy/import",map("title",TITLE,"source",SOURCE,"content","新一段。\n\n新二段。"),true);
        assertThat(failed.path("code").asInt()).isNotEqualTo(200);
        assertThat(state().get("desired_version")).isEqualTo(before.get("desired_version"));
        assertThat(state().get("payload_json")).isEqualTo(before.get("payload_json"));
        assertThat(jdbc.queryForList("SELECT * FROM policy_doc WHERE source=?",SOURCE)).isEqualTo(sourceBefore);
    }

    @Test void concurrentSameDocumentImportAndDeleteWaitForTheSyncRowBeforeChangingSource() throws Exception {
        imported(TITLE,"初版。");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try(Connection barrier=dataSource.getConnection()) {
            barrier.setAutoCommit(false);
            long connection=connectionId(barrier);
            try(PreparedStatement lock=barrier.prepareStatement("SELECT id FROM policy_rag_sync WHERE document_key=? FOR UPDATE")) {
                lock.setString(1,documentKey); lock.executeQuery().close();
            }
            Future<JsonNode> importing=pool.submit(() -> post("/ai/policy/import",map("title",TITLE,"source",SOURCE,"content","并发替换。"),true));
            Future<JsonNode> deleting=pool.submit(() -> delete(TITLE,SOURCE));
            waitForSyncWaiters(connection,2);
            assertThat(jdbc.queryForList("SELECT content FROM policy_doc WHERE source=?",String.class,SOURCE)).containsExactly("初版。");
            barrier.commit();
            ok(importing.get(20,TimeUnit.SECONDS)); ok(deleting.get(20,TimeUnit.SECONDS));
            Map<String,Object> finalRow=state();
            assertThat(((Number)finalRow.get("desired_version")).longValue()).isEqualTo(3);
            JsonNode finalPayload=mapper.readTree((String)finalRow.get("payload_json"));
            assertCanonicalSnapshot(finalPayload);
            List<String> actual=jdbc.queryForList("SELECT content FROM policy_doc WHERE source=? ORDER BY section_no,id",String.class,SOURCE);
            List<String> snapshot=new ArrayList<>();
            for(JsonNode chunk:finalPayload.path("chunks")) snapshot.add(chunk.path("content").asText());
            assertThat(actual).isEqualTo(snapshot);
        } finally { shutdown(pool); }
    }

    @Test void twoWorkersCannotClaimTheSameUnexpiredVersionAndExpiredTakeoverChangesToken() throws Exception {
        imported(TITLE,"领取任务。"); enable();
        PolicyRagSyncService other=context.getAutowireCapableBeanFactory().createBean(PolicyRagSyncService.class);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CyclicBarrier start=new CyclicBarrier(2);
        try {
            Future<PolicyRagSync> a=pool.submit(() -> { start.await(5,TimeUnit.SECONDS); return worker.claimOne(documentKey); });
            Future<PolicyRagSync> b=pool.submit(() -> { start.await(5,TimeUnit.SECONDS); return other.claimOne(documentKey); });
            PolicyRagSync first=a.get(15,TimeUnit.SECONDS), second=b.get(15,TimeUnit.SECONDS);
            List<PolicyRagSync> claimed=new ArrayList<>();
            if(first!=null) claimed.add(first); if(second!=null) claimed.add(second);
            assertThat(claimed).hasSize(1);
            PolicyRagSync original=claimed.get(0);
            PolicyRagSyncService originalWorker=first!=null?worker:other;
            PolicyRagSyncService takeoverWorker=first!=null?other:worker;
            jdbc.update("UPDATE policy_rag_sync SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE document_key=?",documentKey);
            PolicyRagSync takeover=takeoverWorker.claimOne(documentKey);
            assertThat(takeover).isNotNull();
            assertThat(takeover.getLeaseToken()).isGreaterThan(original.getLeaseToken());
            assertThat(takeover.getLeaseOwner()).isNotEqualTo(original.getLeaseOwner());
            assertThat(originalWorker.processClaim(original)).isFalse();
            assertThat(received).isEmpty();
            assertThat(takeoverWorker.processClaim(takeover)).isTrue();
            assertThat(received).hasSize(1);
            assertThat(state().get("status")).isEqualTo("SYNCED");
        } finally { shutdown(pool); }
    }

    @Test void expiredLeaseDuringRpcCannotMarkSyncedOrEmitSuccessAudit() throws Exception {
        imported(TITLE,"租约过期。"); enable();
        PolicyRagSync claim=claim();
        blockPeer();
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> syncing=pool.submit(() -> worker.processClaim(claim));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE policy_rag_sync SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE document_key=?",documentKey);
            release.countDown();
            assertThat(syncing.get(15,TimeUnit.SECONDS)).isFalse();
            assertThat(state().get("status")).isNotEqualTo("SYNCED");
            assertThat(successAudit()).isZero();
        } finally { release.countDown(); shutdown(pool); }
    }

    @Test void completionWaitingForRowLockMustRecheckLeaseAfterDatabaseDeadline() throws Exception {
        imported(TITLE,"完成操作等待期间租约到期。"); enable();
        PolicyRagSync claim=claim();
        blockPeer();
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(Connection barrier=dataSource.getConnection()) {
            barrier.setAutoCommit(false);
            Future<Boolean> syncing=pool.submit(() -> worker.processClaim(claim));
            assertThat(entered.await(10,TimeUnit.SECONDS)).as("claimed RPC reached its peer barrier").isTrue();
            long connection=connectionId(barrier);
            // This uncommitted current row is read by the completion UPDATE only
            // after its lock wait. NOW(3), however, is fixed at statement start.
            try(PreparedStatement edit=barrier.prepareStatement("UPDATE policy_rag_sync "
                    +"SET lease_until=TIMESTAMPADD(SECOND,3,NOW(3)) WHERE document_key=?")) {
                edit.setString(1,documentKey); assertThat(edit.executeUpdate()).isEqualTo(1);
            }
            release.countDown();
            waitForSyncWaiters(connection,1);
            try(PreparedStatement deadline=barrier.prepareStatement("SELECT lease_until<=NOW(3) "
                    +"FROM policy_rag_sync WHERE document_key=?")) {
                deadline.setString(1,documentKey);
                try(ResultSet clock=deadline.executeQuery()) {
                    assertThat(clock.next()).isTrue();
                    assertThat(clock.getBoolean(1)).as("completion began waiting before the actual deadline").isFalse();
                }
                boolean expired=false;
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(!expired && System.nanoTime()<until) {
                    try(ResultSet clock=deadline.executeQuery()) {
                        assertThat(clock.next()).isTrue(); expired=clock.getBoolean(1);
                    }
                    if(!expired) Thread.sleep(10);
                }
                assertThat(expired).as("a fresh MySQL statement confirms lease expiration while the row remains locked").isTrue();
            }
            assertThat(syncing.isDone()).as("completion is still blocked when database time passes its lease").isFalse();
            barrier.commit();
            assertThat(syncing.get(15,TimeUnit.SECONDS)).as("an expired claimant must not complete after acquiring the lock").isFalse();
            assertThat(state().get("status")).isNotEqualTo("SYNCED");
            assertThat(successAudit()).isZero();
        } finally { release.countDown(); shutdown(pool); }
    }

    @Test void newerDesiredVersionDuringRpcRejectsOldCompletionAndKeepsCapturedSnapshot() throws Exception {
        imported(TITLE,"旧版快照。"); enable();
        PolicyRagSync claim=claim();
        blockPeer();
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> syncing=pool.submit(() -> worker.processClaim(claim));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            imported(TITLE,"新版快照。");
            release.countDown();
            assertThat(syncing.get(15,TimeUnit.SECONDS)).isFalse();
            assertThat(received).hasSize(1);
            JsonNode sent=received.get(0); assertCanonicalSnapshot(sent);
            assertThat(sent.path("version").asLong()).isEqualTo(1);
            assertThat(sent.path("chunks").get(0).path("content").asText()).isEqualTo("旧版快照。");
            assertThat(((Number)state().get("desired_version")).longValue()).isEqualTo(2);
            assertThat(state().get("status")).isEqualTo("PENDING");
            assertThat(successAudit()).isZero();
            assertThat(worker.processClaim(claim())).isTrue();
            assertThat(received).hasSize(2);
            assertThat(received.get(1).path("chunks").get(0).path("content").asText()).isEqualTo("新版快照。");
            assertThat(successAudit()).isEqualTo(1);
        } finally { release.countDown(); shutdown(pool); }
    }

    @Test void unacknowledgedOrWrongIdentityResponsesNeverMarkSynced() {
        imported(TITLE,"回显身份。"); enable();
        for(String mode:Arrays.asList("EMPTY","WRONG_KEY","WRONG_VERSION","WRONG_ACTION","WRONG_HASH","NOT_APPLIED")) {
            reply=mode;
            jdbc.update("UPDATE policy_rag_sync SET next_retry_time=NOW(),lease_until=NULL,lease_owner=NULL WHERE document_key=?",documentKey);
            assertThat(worker.processClaim(claim())).as(mode).isFalse();
            assertThat(state().get("status")).as(mode).isNotEqualTo("SYNCED");
            assertThat(successAudit()).as(mode).isZero();
        }
        reply="ECHO";
        jdbc.update("UPDATE policy_rag_sync SET next_retry_time=NOW() WHERE document_key=?",documentKey);
        assertThat(worker.processClaim(claim())).isTrue();
        assertThat(successAudit()).isEqualTo(1);
        assertThat(paths).allMatch("/v2/documents/sync"::equals);
    }

    @Test void reindexWaitsForDocumentLockThenRereadsCommittedSourceForItsHash() throws Exception {
        imported(TITLE,"重建前原文。");
        // Isolate the global reindex operation: it may enqueue existing documents,
        // but every source and sync row outside this test is restored byte-for-byte.
        List<Map<String,Object>> savedSync=jdbc.queryForList("SELECT * FROM policy_rag_sync WHERE source<>?",SOURCE);
        Set<String> outsideKeys=new HashSet<>();
        for(Map<String,Object> row:savedSync) outsideKeys.add((String)row.get("document_key"));
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(Connection barrier=dataSource.getConnection()) {
            barrier.setAutoCommit(false);
            long connection=connectionId(barrier);
            try(PreparedStatement lock=barrier.prepareStatement("SELECT id FROM policy_rag_sync WHERE document_key=? FOR UPDATE")) {
                lock.setString(1,documentKey); lock.executeQuery().close();
            }
            Future<JsonNode> rebuilding=pool.submit(() -> post("/ai/policy/reindex",null,true));
            waitForSyncWaiters(connection,1);
            try(PreparedStatement edit=barrier.prepareStatement("UPDATE policy_doc SET content='重建锁内新原文。' WHERE source=?")) {
                edit.setString(1,SOURCE); edit.executeUpdate();
            }
            barrier.commit(); ok(rebuilding.get(20,TimeUnit.SECONDS));
            JsonNode rebuilt=mapper.readTree((String)state().get("payload_json"));
            assertThat(rebuilt.path("chunks").get(0).path("content").asText()).isEqualTo("重建锁内新原文。");
            assertCanonicalSnapshot(rebuilt);
        } finally {
            shutdown(pool);
            // The suite uses an explicitly disposable database; preserve prior
            // scheduling state even though reindex intentionally addresses all docs.
            jdbc.update("DELETE FROM policy_rag_sync WHERE source<>?",SOURCE);
            for(Map<String,Object> row:savedSync) insertExact("policy_rag_sync",row);
        }
    }

    private void insertExact(String table,Map<String,Object> row) {
        jdbc.update("INSERT INTO "+table+"("+String.join(",",row.keySet())+") VALUES("
                +String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
    }
    private void enable() { ReflectionTestUtils.setField(client,"enabled",true); }
    private PolicyRagSync claim() {
        PolicyRagSync value=worker.claimOne(documentKey);
        assertThat(value).as("a due test-owned document must be claimed").isNotNull(); return value;
    }
    private Map<String,Object> state() { return row(documentKey); }
    private Map<String,Object> row(String key) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM policy_rag_sync WHERE document_key=?",key);
        assertThat(rows).as("desired sync state must exist even with RAG disabled").hasSize(1); return rows.get(0);
    }
    private long successAudit() {
        return number("SELECT COUNT(*) FROM ai_audit_log a JOIN policy_rag_sync s ON a.object_id=s.id "
                +"WHERE s.document_key=? AND a.object_type='policy_rag_sync' AND a.detail LIKE '%结果=SYNCED%'",documentKey);
    }
    private void imported(String title,String text) { ok(post("/ai/policy/import",map("title",title,"source",SOURCE,"content",text),true)); }
    private JsonNode delete(String title,String source) {
        String url=UriComponentsBuilder.fromPath("/ai/policy/document").queryParam("title",title)
                .queryParam("source",source).build().encode().toUriString();
        return request(HttpMethod.DELETE,url,null,true);
    }
    private JsonNode post(String path,Object body,boolean authenticated) { return request(HttpMethod.POST,path,body,authenticated); }
    private JsonNode request(HttpMethod method,String path,Object body,boolean authenticated) {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if(authenticated) headers.set("token",token);
        JsonNode result=http.exchange(URI.create(http.getRootUri()+path),method,new HttpEntity<>(body,headers),JsonNode.class).getBody();
        assertThat(result).as(method+" "+path).isNotNull(); return result;
    }
    private void ok(JsonNode result) { assertThat(result.path("code").asInt()).as(result.toString()).isEqualTo(200); }
    private long number(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    private long connectionId(Connection connection) throws SQLException {
        try(Statement statement=connection.createStatement(); ResultSet row=statement.executeQuery("SELECT CONNECTION_ID()")) {
            row.next(); return row.getLong(1);
        }
    }
    private void waitForSyncWaiters(long blocking,int expected) throws Exception {
        String sql="SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w "
                +"JOIN information_schema.innodb_trx b ON b.trx_id=w.BLOCKING_ENGINE_TRANSACTION_ID "
                +"JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE "
                +"WHERE b.trx_mysql_thread_id=? AND l.OBJECT_NAME='policy_rag_sync'";
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(number(sql,blocking)<expected && System.nanoTime()<deadline) Thread.sleep(10);
        assertThat(number(sql,blocking)).as("real MySQL waiters on the owned sync row").isEqualTo(expected);
    }
    private void blockPeer() { entered=new CountDownLatch(1); release=new CountDownLatch(1); }
    private void respond(HttpExchange exchange) {
        try {
            paths.add(exchange.getRequestURI().getPath());
            JsonNode value=mapper.readTree(exchange.getRequestBody()); received.add(value);
            if(entered!=null) { entered.countDown(); if(!release.await(15,TimeUnit.SECONDS)) throw new AssertionError("HTTP release timed out"); }
            ObjectNode response=mapper.createObjectNode();
            if(!"EMPTY".equals(reply)) {
                for(String field:Arrays.asList("document_key","version","action","payload_hash")) response.set(field,value.path(field));
                response.put("applied",true);
                if("WRONG_KEY".equals(reply)) response.put("document_key","different-key");
                if("WRONG_VERSION".equals(reply)) response.put("version",value.path("version").asLong()+1);
                if("WRONG_ACTION".equals(reply)) response.put("action","DELETE");
                if("WRONG_HASH".equals(reply)) response.put("payload_hash",String.join("",Collections.nCopies(64,"0")));
                if("NOT_APPLIED".equals(reply)) response.put("applied",false);
            }
            byte[] bytes=mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
        } catch(Throwable failure) { peerFailure.compareAndSet(null,failure); }
        finally { exchange.close(); }
    }
    private void assertCanonicalSnapshot(JsonNode value) throws Exception {
        assertThat(value.path("document_key").asText()).isNotBlank();
        assertThat(value.path("version").asLong()).isPositive();
        ObjectMapper canonicalMapper=new ObjectMapper();
        SortedMap<String,Object> canonical=new TreeMap<>();
        for(String name:Arrays.asList("action","document_key","source","title")) canonical.put(name,value.path(name).asText());
        List<JsonNode> ordered=new ArrayList<>(); value.path("chunks").forEach(ordered::add);
        ordered.sort(Comparator.comparingLong((JsonNode row) -> row.path("section_no").asLong())
                .thenComparingLong(row -> row.path("chunk_id").asLong()));
        List<Map<String,Object>> chunks=new ArrayList<>();
        for(JsonNode chunk:ordered) {
            SortedMap<String,Object> row=new TreeMap<>();
            row.put("chunk_id",chunk.path("chunk_id").asLong()); row.put("content",chunk.path("content").asText());
            row.put("section_no",chunk.path("section_no").asLong()); chunks.add(row);
        }
        canonical.put("chunks",chunks);
        byte[] bytes=canonicalMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder expected=new StringBuilder(); for(byte part:hash) expected.append(String.format("%02x",part&255));
        assertThat(value.path("payload_hash").asText()).isEqualTo(expected.toString());
    }
    private static Map<String,Object> map(Object... entries) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<entries.length;i+=2) result.put((String)entries[i],entries[i+1]); return result;
    }
    private void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        if(!pool.awaitTermination(20,TimeUnit.SECONDS)) { pool.shutdownNow(); throw new AssertionError("RAG worker did not stop after releasing barriers"); }
    }
}
