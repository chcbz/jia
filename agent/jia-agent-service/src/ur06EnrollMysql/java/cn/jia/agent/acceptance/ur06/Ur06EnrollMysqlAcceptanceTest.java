package cn.jia.agent.acceptance.ur06;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** UR06 E1-E5/M1-M3. Main runs only in the awaited C1-C4 same-target callback.
 * Each selector owns a fresh MySQL datadir; A/B are genuine separate JVMs and
 * independent JDBC connections. No H2/mockDAO/credential preseed/Provider/Rabbit.
 * Six M4 methods retain the complete original E05 oracles on genuine owned MySQL.
 * Source existence/compilation does not close UR06; Main owns actual seven+six execution. */
class Ur06EnrollMysqlAcceptanceTest {
    private static final String BASELINE="fcac494a5103a7bc1175c96e9df50d2fcc35c182";
    private static final String CLIENT="c8be411be9819e6139628ac3cd12f040eeb171d9";
    private String stage="SOURCE";
    private interface Checked { void run() throws Exception; }
    private void safe(Checked work) throws Exception {
        try { work.run(); }
        catch(Throwable failure) {
            // Never attach the original cause: JDBC assertions/HTTP/files may contain secrets.
            Map<String,Object> diagnostic=Ur06EnrollMysqlFixture.safeFailure(failure);
            if (failure instanceof SafeFailure s) diagnostic=Map.of("code",s.code);
            throw new AssertionError("UR06_"+stage+" "+Ur06EnrollMysqlFixture.JSON.writeValueAsString(diagnostic));
        }
    }
    @Test void e1OriginalPendingEnrollNestedCredentialDurability() throws Exception {
        safe(()-> {try(Lane f=new Lane()) {
            f.create(0, 3_600_000); Installation pending=f.installation(0); assertPending(pending);
            JsonNode enrolled=f.enroll(0,"state",null,f.secrets.get(0)); assertEnrolled(enrolled);
            Installation active=f.installation(0); assertActive(active,pending);
            f.assertCredential(0,"state",active);
            List<JsonNode> requests=f.http("ENROLL",0); assertEquals(1,requests.size());
            assertEquals(200,n(requests.getFirst(),"status")); assertTrue(b(requests.getFirst(),"nested")); assertTrue(b(requests.getFirst(),"noStore"));
            long a=f.java.process.pid(); f.java.stop("STOPPED");
            assertTrue(active.same(f.installation(0))); // fresh parent JDBC after A exits, not same transaction
            f.restartJava(); assertNotEquals(a,f.java.process.pid()); assertTrue(active.same(f.installation(0)));
            JsonNode duplicate=f.enroll(0,"state",null,f.secrets.get(0)); assertFalse(b(duplicate,"success"));
            assertEquals("RUNTIME_ENROLLMENT_AUTHORIZATION_EXISTS",t(duplicate,"code")); assertEquals(0,f.http("ENROLL",0).size());
            f.receipt("E1",Map.of("nested",true,"consumedOnce",true,"independentJdbc",true));
        }});
    }
    @Test void e2RealNativeRegistrationSeparateJvmGenerationAndSibling() throws Exception {
        safe(()-> {try(Lane f=new Lane()) {
            f.enrollPair(); f.startHost(); JsonNode ready=f.awaitReady(2); assertReady(ready,0); assertReady(ready,1);
            long first=n(agent(ready,0),"generation"); assertEquals(1,first);
            JsonNode old=f.host.exchange(Map.of("op","PRIVATE_PROOF","agentIndex",0),"PRIVATE_PROOF").get("headers");
            f.assertRuntimeProof(0,old);
            assertEquals(404,n(f.host.exchange(Map.of("op","NATIVE","agentIndex",0),"NATIVE_RESULT"),"status"));
            assertEquals(1,f.http("NATIVE",0).size()); assertTrue(b(f.http("NATIVE",0).getFirst(),"noStore"));
            Installation installed=f.installation(0); long apiA=f.java.process.pid(),nodeA=f.host.process.pid();
            f.host.stop("NODE_STOPPED"); f.java.stop("STOPPED"); assertTrue(installed.sameAuthorization(f.installation(0)));
            f.restartJava(); f.startHost(); JsonNode restarted=f.awaitReady(2);
            assertNotEquals(apiA,f.java.process.pid()); assertNotEquals(nodeA,f.host.process.pid()); assertEquals(first+1,n(agent(restarted,0),"generation"));
            f.assertRuntimeProof(0,f.host.exchange(Map.of("op","PRIVATE_PROOF","agentIndex",0),"PRIVATE_PROOF").get("headers"));
            long rows=f.deliveryCount();
            assertEquals(401,n(f.host.exchange(Map.of("op","STALE_NATIVE","agentIndex",0,"headers",old),"NATIVE_RESULT"),"status"));
            assertEquals(rows,f.deliveryCount()); assertTrue(installed.sameAuthorization(f.installation(0))); assertReady(f.hostSnapshot(),1);
            // Real old-proof WS handshake after B starts. Parent observes rejection via
            // original installed ws implementation, not a fabricated ready Supplier.
            JsonNode ws=f.rawProbe(old); assertEquals(401,n(ws,"status"));
            f.receipt("E2_M2",Map.of("nativeRegistered",true,"jvmRestart",true,"generationIncrement",true,"oldProofDenied",true,"siblingReady",true));
        }});
    }
    @Test void e3WrongExpiredManifestAndSubjectDeniedWithoutConsumption() throws Exception {
        safe(()-> {try(Lane f=new Lane()) {
            for(int i=2;i<=5;i++) {
                f.create(i,i==3 ? 150 : 3_600_000); Installation before=f.installation(i); assertPending(before);
                if(i==3) { while(System.currentTimeMillis()<=before.expires) Thread.sleep(10); } // expiry semantics, not a performance gate
                String variant=i==4 ? "MANIFEST" : i==5 ? "SUBJECT" : null;
                String secret=i==2 ? randomSecret() : f.secrets.get(i);
                JsonNode denied=f.enroll(i,"state",variant,secret); assertFalse(b(denied,"success"));
                assertEquals("RUNTIME_ENROLLMENT_RECOVERY_REQUIRED",t(denied,"code")); assertFalse(b(denied,"authorizationExists")); assertTrue(b(denied,"markerPublicOnly"));
                assertTrue(before.same(f.installation(i))); List<JsonNode> http=f.http("ENROLL",i); assertEquals(1,http.size()); assertEquals(403,n(http.getFirst(),"status"));
            }
            f.receipt("E3",Map.of("real403",4,"unchanged",true));
        }});
    }
    @Test void e4IndependentStateConcurrentSecretCasAndReplayDenial() throws Exception {
        safe(()-> {try(Lane f=new Lane()) {
            f.create(6,3_600_000); Installation before=f.installation(6);
            Child left=f.enrollChild(6,"race-1",null,f.secrets.get(6),true); Child right=f.enrollChild(6,"race-2",null,f.secrets.get(6),true);
            left.receipt("ENROLL_READY"); right.receipt("ENROLL_READY");
            // Hold the actual installation row until BOTH real service transactions
            // are observed in MySQL LOCK WAIT. This is server concurrency, not just
            // two marker attempts or a timing/sleep assertion.
            try(Connection lock=f.database.getConnection()) {
                lock.setAutoCommit(false);
                try(PreparedStatement p=lock.prepareStatement("SELECT id FROM agent_runtime_v1_installation WHERE installation_id=? FOR UPDATE")) {
                    p.setString(1,Ur06EnrollMysqlFixture.INSTALLATIONS[6]);try(ResultSet rs=p.executeQuery()){assertTrue(rs.next());}
                }
                try {
                    left.send(Map.of("op","START"));right.send(Map.of("op","START"));
                    f.mysql.awaitEnrollLockWait(left,right);
                } finally { lock.rollback(); }
            }
            JsonNode l=left.receipt("ENROLLED"),r=right.receipt("ENROLLED"); left.exitZero(); right.exitZero();
            assertEquals(1,(b(l,"success")?1:0)+(b(r,"success")?1:0));
            JsonNode lost=b(l,"success")?r:l; assertEquals("RUNTIME_ENROLLMENT_RECOVERY_REQUIRED",t(lost,"code"));
            Installation active=f.installation(6); assertActive(active,before);
            String winner=b(l,"success")?"race-1":"race-2"; f.assertCredential(6,winner,active);
            List<Long> statuses=f.http("ENROLL",6).stream().map(x->n(x,"status")).sorted().toList(); assertEquals(List.of(200L,403L),statuses);
            JsonNode replay=f.enroll(6,"race-3",null,f.secrets.get(6)); assertFalse(b(replay,"success")); assertEquals(403,n(f.http("ENROLL",6).getLast(),"status"));
            int count=f.http("ENROLL",6).size(); JsonNode local=f.enroll(6,winner,null,f.secrets.get(6));
            assertEquals("RUNTIME_ENROLLMENT_AUTHORIZATION_EXISTS",t(local,"code")); assertEquals(count,f.http("ENROLL",6).size());
            assertTrue(active.same(f.installation(6)));
            f.receipt("E4_M2",Map.of("requests",3,"activations",1,"localReplayNoHttp",true,"mysqlCas",true));
        }});
    }
    @Test void e5RealCommittedResponseLossRestartNeverReplaysSecret() throws Exception {
        safe(()-> {try(Lane f=new Lane()) {
            f.create(7,3_600_000); Installation pending=f.installation(7);
            f.java.exchange(Map.of("op","ARM_CUT","agentIndex",7),"CUT_ARMED");
            Child attempt=f.enrollChild(7,"state",null,f.secrets.get(7));
            assertTrue(b(f.java.receipt("ENROLL_COMMIT_HELD"),"committed"));
            Installation committed=f.installation(7); assertActive(committed,pending);
            long apiA=f.java.process.pid(),nodeA=attempt.process.pid(); f.java.crash();
            JsonNode unknown=attempt.receipt("ENROLLED"); attempt.exitZero();
            assertEquals("RUNTIME_ENROLLMENT_RECOVERY_REQUIRED",t(unknown,"code")); assertFalse(b(unknown,"authorizationExists")); assertTrue(b(unknown,"markerAttempted")); assertTrue(b(unknown,"markerPublicOnly"));
            assertTrue(committed.same(f.installation(7))); f.restartJava(); assertNotEquals(apiA,f.java.process.pid());
            Child retry=f.enrollChild(7,"state",null,f.secrets.get(7)); assertNotEquals(nodeA,retry.process.pid());
            JsonNode blocked=retry.receipt("ENROLLED"); retry.exitZero();
            assertEquals("RUNTIME_ENROLLMENT_RECOVERY_REQUIRED",t(blocked,"code")); assertFalse(b(blocked,"authorizationExists"));
            assertEquals(0,f.http("ENROLL",7).size()); assertTrue(committed.same(f.installation(7)));
            f.receipt("E5_M2",Map.of("commitBeforeTcpLoss",true,"freshNodeAndJvm",true,"markerPreserved",true,"replayedRequests",0));
        }});
    }
    @Test void m1OriginalMysqlInitializersRepeatNullableFenceAndConstraints() throws Exception {
        safe(()-> {try(Lane f=new Lane()) {
            f.create(0,3_600_000); f.create(1,3_600_000); assertPending(f.installation(0)); assertPending(f.installation(1));
            JdbcTemplate jdbc=f.jdbc();
            Map<String,Object> table=jdbc.queryForMap("SELECT ENGINE,TABLE_COLLATION FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_runtime_v1_installation'");
            assertEquals("InnoDB",table.get("ENGINE")); assertEquals("utf8mb4_0900_bin",table.get("TABLE_COLLATION"));
            for(String column:List.of("enrollment_secret_hash","runtime_authorization_hash")) {
                Map<String,Object> c=jdbc.queryForMap("SELECT DATA_TYPE,CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_runtime_v1_installation' AND column_name=?",column);
                assertEquals("binary",c.get("DATA_TYPE")); assertEquals(32,((Number)c.get("CHARACTER_MAXIMUM_LENGTH")).longValue());
            }
            for(String name:List.of("uk_runtime_v1_installation","uk_runtime_v1_authorization")) assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='agent_runtime_v1_installation' AND index_name=? AND non_unique=0",Integer.class,name));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='agent_runtime_v1_installation' AND constraint_name='chk_runtime_v1_status' AND constraint_type='CHECK' AND enforced='YES'",Integer.class));
            List<String> names=List.of("runtime_installation_id","runtime_host_id","runtime_instance_id","runtime_session_generation");
            for(int i=0;i<4;i++) {
                Map<String,Object> c=jdbc.queryForMap("SELECT DATA_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_runtime' AND column_name=?",names.get(i));
                assertEquals(i==3?"bigint":"varchar",c.get("DATA_TYPE")); assertEquals("YES",c.get("IS_NULLABLE")); if(i<3) assertEquals(100,((Number)c.get("CHARACTER_MAXIMUM_LENGTH")).longValue());
            }
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime WHERE agent_id='ur06-legacy' AND runtime_installation_id IS NULL AND runtime_host_id IS NULL AND runtime_instance_id IS NULL AND runtime_session_generation IS NULL AND create_time=17 AND update_time=17",Integer.class));
            assertConstraint(f,"UPDATE agent_runtime_v1_installation SET status='INVALID' WHERE installation_id=?",3819,Ur06EnrollMysqlFixture.INSTALLATIONS[0]);
            assertConstraint(f,"UPDATE agent_runtime_v1_installation SET installation_id=? WHERE installation_id=?",1062,Ur06EnrollMysqlFixture.INSTALLATIONS[0],Ur06EnrollMysqlFixture.INSTALLATIONS[1]);
            try(Connection c=f.database.getConnection()) {
                c.setAutoCommit(false);
                try(PreparedStatement p=c.prepareStatement("UPDATE agent_runtime_v1_installation SET runtime_authorization_hash=? WHERE installation_id=?")) {
                    byte[] probe=new byte[32]; Arrays.fill(probe,(byte)42); p.setBytes(1,probe);p.setString(2,Ur06EnrollMysqlFixture.INSTALLATIONS[0]);assertEquals(1,p.executeUpdate());
                    p.setString(2,Ur06EnrollMysqlFixture.INSTALLATIONS[1]);SQLException conflict=assertThrows(SQLException.class,p::executeUpdate);assertEquals(1062,conflict.getErrorCode());
                } finally {c.rollback();}
            }
            Installation before=f.installation(0); assertPending(before);
            assertTrue(b(f.java.exchange(Map.of("op","REPEAT_SCHEMA"),"SCHEMA_REPEATED"),"unchanged")); assertTrue(before.same(f.installation(0)));
            // Genuine MySQL FOR UPDATE serialization: independent locker + independent writer,
            // verified blocked by information_schema.innodb_trx, not a guessed sleep/latch.
            try(Connection lock=f.database.getConnection();Connection writer=f.database.getConnection()) {
                lock.setAutoCommit(false); writer.setAutoCommit(false);
                try(PreparedStatement p=lock.prepareStatement("SELECT id FROM agent_runtime_v1_installation WHERE installation_id=? FOR UPDATE")) {p.setString(1,Ur06EnrollMysqlFixture.INSTALLATIONS[0]);try(ResultSet rs=p.executeQuery()){assertTrue(rs.next());}}
                // A schema-only test principal has no PROCESS privilege; use performance_schema
                // row-lock proof via the fixture's separately guarded administrative connection.
                ExecutorService work=Executors.newSingleThreadExecutor(); Future<Integer> update=null;
                try {
                    update=work.submit(()->{try(PreparedStatement p=writer.prepareStatement("UPDATE agent_runtime_v1_installation SET last_heartbeat_at=123 WHERE installation_id=?")){p.setString(1,Ur06EnrollMysqlFixture.INSTALLATIONS[0]);return p.executeUpdate();}});
                    f.mysql.awaitLockWait(update); assertFalse(update.isDone()); lock.rollback(); assertEquals(1,update.get()); writer.rollback();
                } finally {lock.rollback();writer.rollback();work.shutdown();while(!work.awaitTermination(100,TimeUnit.MILLISECONDS)) { /* await owned JDBC worker */ }}
            }
            assertTrue(before.same(f.installation(0))); f.receipt("M1",Map.of("mysql8021",true,"originalInitializers",true,"repeatUnchanged",true,"nullableFence",4,"negativeConstraints",3,"realLockWait",true));
        }});
    }
    @Test void m3NativeCurrentFenceD06ReceiptsRevocationAndSiblingIsolation() throws Exception {
        safe(()-> {try(Lane f=new Lane()) {
            f.enrollPair(); f.startHost(); f.awaitReady(2);
            JsonNode command=f.java.exchange(Map.of("op","SEED_ACK","agentIndex",0),"ACK_SEEDED").get("command");
            assertAck(f.ack(0,command,"RECEIVED",null),"ADVANCED","RECEIVED",8);
            assertAck(f.ack(0,command,"RECEIVED",8L),"PRIOR","RECEIVED",8);
            assertAck(f.ack(0,command,"STARTED",8L),"ADVANCED","STARTED",9);
            JsonNode priorProof=f.host.exchange(Map.of("op","PRIVATE_PROOF","agentIndex",0),"PRIVATE_PROOF").get("headers");
            JsonNode rotated=f.host.exchange(Map.of("op","ROTATE","agentIndex",0),"ROTATED");assertEquals(2,n(rotated,"generation"));
            assertEquals(401,n(f.host.exchange(Map.of("op","STALE_ACK","agentIndex",0,"headers",priorProof,"command",command,"status","SUCCEEDED","deliveryVersion",9),"ACK_RESULT"),"httpStatus"));
            assertDelivery(f,0,"STARTED",9); assertEquals(401,n(f.host.exchange(Map.of("op","STALE_NATIVE","agentIndex",0,"headers",priorProof),"NATIVE_RESULT"),"status"));
            f.host.exchange(Map.of("op","DISCONNECT","agentIndex",0),"DISCONNECTED"); assertEquals(3,n(agent(f.awaitReady(2),0),"generation"));
            assertAck(f.ack(0,command,"SUCCEEDED",9L),"ADVANCED","SUCCEEDED",10);
            assertAck(f.ack(0,command,"SUCCEEDED",10L),"PRIOR","SUCCEEDED",10);
            // Malformed version must not confirm or advance even a terminal duplicate.
            JsonNode invalid=f.ack(0,command,"SUCCEEDED",11L);assertFalse(b(invalid,"success"));assertDelivery(f,0,"SUCCEEDED",10);
            List<JsonNode> ackHttp=f.http("ACK",0).stream().filter(r->n(r,"status")==200).toList();
            assertEquals(5,ackHttp.size());assertTrue(ackHttp.getFirst().get("lastConfirmedVersion").isNull());
            assertEquals(List.of(8L,8L,9L,10L),ackHttp.subList(1,5).stream().map(r->n(r,"lastConfirmedVersion")).toList());
            JsonNode sibling=f.java.exchange(Map.of("op","SEED_ACK","agentIndex",1),"ACK_SEEDED").get("command");
            JsonNode current=f.host.exchange(Map.of("op","PRIVATE_PROOF","agentIndex",0),"PRIVATE_PROOF").get("headers");
            f.java.exchange(Map.of("op","REVOKE","agentIndex",0),"REVOKED");
            assertEquals(401,n(f.host.exchange(Map.of("op","STALE_NATIVE","agentIndex",0,"headers",current),"NATIVE_RESULT"),"status"));
            assertEquals(401,n(f.host.exchange(Map.of("op","STALE_ACK","agentIndex",0,"headers",current,"command",command,"status","SUCCEEDED","deliveryVersion",10),"ACK_RESULT"),"httpStatus"));
            assertDelivery(f,0,"SUCCEEDED",10); assertEquals(404,n(f.host.exchange(Map.of("op","NATIVE","agentIndex",1),"NATIVE_RESULT"),"status")); assertAck(f.ack(1,sibling,"RECEIVED",null),"ADVANCED","RECEIVED",8);assertReady(f.hostSnapshot(),1);
            f.host.exchange(Map.of("op","DISCONNECT","agentIndex",0),"DISCONNECTED");
            f.waitHost(s->b(agent(s,0),"isolated"));assertFalse(b(agent(f.hostSnapshot(),0),"ready"));assertReady(f.hostSnapshot(),1);
            f.host.stop("NODE_STOPPED"); f.java.stop("STOPPED"); assertDelivery(f,0,"SUCCEEDED",10);assertDelivery(f,1,"RECEIVED",8);
            f.receipt("M3",Map.of("advanced",4,"prior",2,"staleAndRevokedDenied",true,"siblingReady",true,"rabbit","NOT_RUN","businessExecution","NOT_RUN"));
        }
        for(String variant:List.of("HASH","LEASE","MESSAGE")) {try(Lane f=new Lane()) {
            f.enrollPair(); f.startHost();f.awaitReady(2);JsonNode command=f.java.exchange(Map.of("op","SEED_ACK","agentIndex",0),"ACK_SEEDED").get("command");
            f.java.exchange(Map.of("op","MUTATE_SOURCE","agentIndex",0,"variant",variant),"SOURCE_MUTATED");
            JsonNode denied=f.ack(0,command,"RECEIVED",null);assertFalse(b(denied,"success"));assertEquals(403,n(denied,"httpStatus"));assertDelivery(f,0,"SENT",7);
            f.receipt("M3_SOURCE_"+variant,Map.of("rejected",true,"versionUnchanged",true));
        }}});
    }
    private static void assertConstraint(Lane f,String sql,int expected,Object...values) throws Exception {
        try(Connection c=f.database.getConnection()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);SQLException failure=assertThrows(SQLException.class,p::executeUpdate);assertEquals(expected,failure.getErrorCode());
        }finally{c.rollback();}}
    }
    @Test void e05ActualClientHttpCommitsOriginalResultAndTerminalAck() throws Exception {
        safe(() -> { try (Lane f = new Lane("NONE")) {
            JsonNode command = f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0);
            f.terminal(0); JsonNode before = f.serverSnapshot(); assertCommitted(before, 0, 1);
            assertD06Sequence(before, 0); assertEquals(1, children(f.nodeSnapshot(), 0));
            JsonNode original = f.node.exchange(Map.of("op", "ORIGINAL_READBACK", "agentIndex", 0, "commandId", text(command, "commandId")), "ORIGINAL_READBACK");
            assertTrue(bool(original, "completed")); assertTrue(text(original, "materialDigest").matches("[0-9a-f]{64}"));
            JsonNode after = f.serverSnapshot(); assertEquals(text(before, "businessSha256"), text(after, "businessSha256"));
            assertTrue(http(after).stream().anyMatch(r -> "RESULT".equals(optional(r, "category")) && "GET".equals(optional(r, "method")) && bool(r, "readOnly")));
            JsonNode duplicate = f.node.exchange(Map.of("op", "DUPLICATE_ACK", "agentIndex", 0, "commandId", text(command, "commandId")), "DUPLICATE_ACK");
            assertEquals("PRIOR", text(duplicate, "kind")); assertEquals(10, integer(duplicate, "deliveryVersion"));
            f.dispatch(0); assertTrue(bool(f.java.exchange(Map.of("op", "ALTERED_DISPATCH", "agentIndex", 0), "DISPATCHED"), "sent"));
            JsonNode conflictObserved = f.waitNode(n -> array(agent(n, 0), "conflicts").size() == 1 && array(agent(n, 0), "pending").isEmpty());
            JsonNode conflict = array(agent(conflictObserved, 0), "conflicts").getFirst();
            assertEquals(text(command, "commandId"), text(conflict, "commandId"));
            assertNotEquals(text(conflict, "existingFingerprint"), text(conflict, "conflictingFingerprint"));
            assertEquals(text(array(agent(conflictObserved, 0), "ledger").getFirst(), "fingerprint"), text(conflict, "existingFingerprint"));
            JsonNode finalState = f.serverSnapshot(); assertEquals(integer(before, "ackUpdates"), integer(finalState, "ackUpdates"));
            assertEquals(text(before, "businessSha256"), text(finalState, "businessSha256")); assertEquals(1, children(f.nodeSnapshot(), 0));
            assertUniqueCheckpoint(f.nodeSnapshot(), 0, text(command, "commandId"));
            f.stopBoth(); assertCommitted(f.persisted(), 0, 1);
        }});
    }

    @Test void e05LostResultReadbackSurvivesJavaAndNodeRestart() throws Exception {
        safe(() -> { try (Lane f = new Lane("RESULT_LOSS")) {
            f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0);
            JsonNode held = f.java.receipt("RESULT_COMMIT_HELD"); assertCommittedBusiness(held.get("snapshot"), 0, 1);
            long javaA = f.java.process.pid(), nodeA = f.node.process.pid(); f.java.crash();
            JsonNode recovery = f.waitNode(s -> hasInbox(s, 0, "recovery_required", true));
            assertTrue(bool(agent(recovery, 0), "credentialsAbsent")); assertEquals(1, children(recovery, 0));
            f.node.stop("NODE_STOPPED"); JsonNode persisted = f.persisted(); assertCommittedBusiness(persisted, 0, 1);
            assertEquals(9, integer(persisted.get("deliveries").get(0), "version"));
            String business = text(persisted, "businessSha256"); f.restart(); assertNotEquals(javaA, f.java.process.pid()); assertNotEquals(nodeA, f.node.process.pid());
            f.terminal(0); JsonNode result = f.serverSnapshot(); assertCommitted(result, 0, 1);
            assertEquals(business, text(result, "businessSha256")); assertEquals(1, children(f.nodeSnapshot(), 0));
            assertRecoveryOnly(result, false); assertEquals(2, integer(result.get("runtimes").get(0), "generation"));
            f.stopBoth(); assertEquals(business, text(f.persisted(), "businessSha256"));
        }});
    }

    @Test void e05TerminalAckLostResponseReplaysPriorWithoutWorkRerun() throws Exception {
        safe(() -> { try (Lane f = new Lane("ACK_LOSS")) {
            f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0);
            JsonNode held = f.java.receipt("TERMINAL_ACK_HELD"); assertCommitted(held.get("snapshot"), 0, 1);
            f.java.crash(); JsonNode pending = f.waitNode(n -> !array(agent(n, 0), "pending").isEmpty());
            assertTrue(array(agent(pending, 0), "pending").size() >= 1); assertEquals(1, children(pending, 0));
            f.node.stop("NODE_STOPPED"); JsonNode original = f.persisted(); String business = text(original, "businessSha256");
            long oldNode = f.node.process.pid(), oldJava = f.java.process.pid(); f.restart();
            assertNotEquals(oldNode, f.node.process.pid()); assertNotEquals(oldJava, f.java.process.pid()); f.terminal(0);
            JsonNode recovered = f.serverSnapshot(); assertCommitted(recovered, 0, 1); assertRecoveryOnly(recovered, true);
            assertEquals(0, integer(recovered, "ackUpdates")); assertEquals(business, text(recovered, "businessSha256"));
            assertEquals(1, children(f.nodeSnapshot(), 0)); assertUniqueCheckpoint(f.nodeSnapshot(), 0, text(f.commands.get(0), "commandId"));
            f.stopBoth(); assertEquals(business, text(f.persisted(), "businessSha256"));
        }});
    }

    @Test void e05PreparedCommitRefencesAndRollbackIsDurable() throws Exception {
        safe(() -> {
            try (Lane f = new Lane("PREPARED")) {
                f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0); f.java.receipt("PREPARED_HELD");
                JsonNode prior = f.serverSnapshot(); assertEquals(0, integer(prior, "artifacts")); assertEquals(0, integer(prior, "submittedEvents"));
                assertEquals(2, integer(f.node.exchange(Map.of("op", "ROTATE", "agentIndex", 0), "ROTATED"), "generation"));
                f.java.exchange(Map.of("op", "RELEASE_PREPARED"), "PREPARED_RELEASED");
                f.waitNode(s -> hasInbox(s, 0, "recovery_required", true)); JsonNode denied = f.serverSnapshot();
                assertEquals(0, integer(denied, "artifacts")); assertEquals(0, integer(denied, "submittedEvents"));
                assertTrue(http(denied).stream().anyMatch(r -> "RESULT".equals(optional(r, "category")) && "POST".equals(optional(r, "method")) && integer(r, "status") == 401));
                f.stopBoth(); JsonNode durable = f.persisted(); assertEquals(0, integer(durable, "artifacts")); assertEquals(0, integer(durable, "submittedEvents"));
            }
            try (Lane f = new Lane("ROLLBACK")) {
                f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0);
                f.waitNode(s -> hasInbox(s, 0, "recovery_required", true)); JsonNode rolled = f.serverSnapshot();
                assertTrue(integer(rolled, "artifactInserts") >= 1); assertTrue(integer(rolled, "submittedAttempts") >= 1); assertTrue(integer(rolled, "workUpdates") >= 3);
                assertEquals(0, integer(rolled, "artifacts")); assertEquals(0, integer(rolled, "submittedEvents"));
                assertEquals(integer(rolled, "events"), integer(rolled, "eventVersion")); assertEquals("running", text(rolled.get("work").get(0), "status"));
                assertFalse(bool(rolled.get("work").get(0), "hasResult")); assertNoSucceeded(rolled);
                JsonNode notFound = f.node.exchange(Map.of("op", "HTTP", "agentIndex", 0, "method", "GET", "path", resultPath(f.commands.get(0))), "HTTP_RESULT");
                assertEquals(404, integer(notFound, "status")); f.stopBoth(); JsonNode persisted = f.persisted();
                assertEquals(text(rolled, "businessSha256"), text(persisted, "businessSha256")); assertEquals(0, integer(persisted, "artifacts"));
                f.restart(); JsonNode recovery = f.nodeSnapshot(); assertTrue(hasInbox(recovery, 0, "recovery_required", true));
                assertEquals(1, children(recovery, 0)); assertRecoveryOnly(f.serverSnapshot(), false);
            }
        });
    }

    @Test void e05SourceAndOriginalResultProofStayFailClosed() throws Exception {
        safe(() -> {
            for (String mutation : List.of("SUCCESSOR_HASH", "PREDECESSOR_HASH", "SOURCE_TARGET")) {
                try (Lane f = new Lane("NONE")) {
                    f.seed(0); f.dispatch(0); f.awaitRealHeartbeat(0);
                    // D06 independently validates canonical source. Corrupt only after its real
                    // STARTED and lease/heartbeat, to isolate original result-source final proof.
                    f.mutate(0, mutation);
                    f.node.exchange(Map.of("op", "RELEASE", "agentIndex", 0), "CHILD_RELEASED");
                    f.waitNode(s -> hasInbox(s, 0, "recovery_required", true));
                    JsonNode rejected = f.serverSnapshot(); assertEquals(0, integer(rejected, "artifacts")); assertEquals(0, integer(rejected, "submittedEvents")); assertNoSucceeded(rejected);
                    long expectedStatus = mutation.equals("SOURCE_TARGET") ? 404 : 409;
                    assertTrue(http(rejected).stream().anyMatch(r -> "RESULT".equals(optional(r, "category")) && expectedStatus == integer(r, "status")));
                    f.stopBoth(); assertEquals(0, integer(f.persisted(), "artifacts"));
                }
            }
            for (String mutation : List.of("PROOF", "MATERIAL")) {
                try (Lane f = new Lane("RESULT_LOSS")) {
                    f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0); f.java.receipt("RESULT_COMMIT_HELD");
                    // Corrupt actual committed proof/material before dropping the real response.
                    f.mutate(0, mutation); f.java.crash(); f.waitNode(s -> hasInbox(s, 0, "recovery_required", true)); f.node.stop("NODE_STOPPED");
                    String fingerprint = text(f.persisted(), "businessSha256"); f.restart();
                    f.node.exchange(Map.of("op", "RECOVER", "agentIndex", 0), "RECOVERY_OBSERVED");
                    assertTrue(hasInbox(f.nodeSnapshot(), 0, "recovery_required", true)); assertNoSucceeded(f.serverSnapshot());
                    assertEquals(fingerprint, text(f.serverSnapshot(), "businessSha256")); assertRecoveryOnly(f.serverSnapshot(), false); assertEquals(1, children(f.nodeSnapshot(), 0));
                }
            }
            for (String variant : List.of("ACTOR", "SCOPE", "OLD_SESSION", "UNREGISTERED", "PRODUCER")) {
                try (Lane f = new Lane("ROLLBACK")) {
                    JsonNode command = f.seed(0);
                    if (variant.equals("PRODUCER")) { f.dispatch(0); f.releaseAfterRealHeartbeat(0); f.waitNode(s -> hasInbox(s, 0, "recovery_required", true)); }
                    JsonNode prior = f.serverSnapshot();
                    JsonNode denied = f.node.exchange(Map.of("op", "NEGATIVE_HTTP", "agentIndex", 0, "variant", variant, "command", command), "NEGATIVE_HTTP");
                    assertEquals(Set.of("ACTOR", "PRODUCER").contains(variant) ? 403 : 401, integer(denied, "status"));
                    assertEquals(text(prior, "businessSha256"), text(f.serverSnapshot(), "businessSha256")); assertNoSucceeded(f.serverSnapshot());
                }
            }
        });
    }

    @Test void e05UnknownStartedNeverRerunsAndSiblingRemainsReady() throws Exception {
        safe(() -> { try (Lane f = new Lane("NONE")) {
            JsonNode original = f.seed(0); f.dispatch(0);
            JsonNode running = f.waitNode(s -> {
                if (hasInbox(s, 0, "recovery_required", false) || hasTerminalFailure(s, 0))
                    throw new SafeFailure("ORIGINAL_EXECUTION_FAILED_BEFORE_CHILD");
                return children(s, 0) == 1;
            }); f.trackChildren(running);
            JsonNode before = f.serverSnapshot(); assertEquals("STARTED", text(before.get("deliveries").get(0), "status"));
            assertFalse(hasInbox(running, 0, "processing", true)); f.node.crash(); f.java.stop("STOPPED");
            JsonNode durable = f.persisted(); assertEquals(0, integer(durable, "artifacts")); assertEquals(0, integer(durable, "submittedEvents"));
            f.restart(); JsonNode unknown = f.nodeSnapshot(); assertTrue(hasInbox(unknown, 0, "recovery_required", false)); assertEquals(1, children(unknown, 0));
            f.node.exchange(Map.of("op", "RECOVER", "agentIndex", 0), "RECOVERY_OBSERVED");
            assertEquals(1, children(f.nodeSnapshot(), 0)); assertNoSucceeded(f.serverSnapshot());
            assertTrue(bool(agent(f.nodeSnapshot(), 1), "ready")); assertTrue(bool(agent(f.nodeSnapshot(), 2), "ready"));
            f.mutate(0, "REVOKE");
            JsonNode revoked = f.node.exchange(Map.of("op", "HTTP", "agentIndex", 0, "method", "GET", "path", resultPath(original)), "HTTP_RESULT");
            assertEquals(401, integer(revoked, "status"));
            // Exercise original reconnect/session rejection after the actual private DB revocation.
            f.node.exchange(Map.of("op", "DISCONNECT", "agentIndex", 0), "SUBJECT_DISCONNECTED");
            JsonNode isolatedSubject = f.waitNode(n -> bool(agent(n, 0), "isolated"));
            assertFalse(bool(agent(isolatedSubject, 0), "ready"));
            f.seed(1); f.dispatch(1); f.releaseAfterRealHeartbeat(1); f.terminal(1);
            JsonNode isolated = f.serverSnapshot(); assertEquals(1, integer(isolated, "artifacts")); assertEquals(1, integer(isolated, "submittedEvents"));
            assertEquals("SUCCEEDED", text(isolated.get("deliveries").get(1), "status")); assertNotEquals("SUCCEEDED", text(isolated.get("deliveries").get(0), "status"));
            JsonNode node = f.nodeSnapshot(); assertEquals(1, children(node, 0)); assertEquals(1, children(node, 1)); assertEquals(0, children(node, 2));
            for (JsonNode a : array(node, "agents")) assertTrue(bool(a, "credentialsAbsent"));
            assertTrue(bool(agent(node, 2), "ready")); f.trackChildren(node);
        }});
    }

    private static void assertPending(Installation r){assertEquals("PENDING",r.status);assertEquals(0,r.version);assertNull(r.consumed);assertNull(r.issued);assertNull(r.authorization);assertEquals(32,r.secret.length);}
    private static void assertActive(Installation a,Installation p){assertEquals("ACTIVE",a.status);assertEquals(1,a.version);assertNotNull(a.consumed);assertNotNull(a.issued);assertEquals(32,a.authorization.length);assertTrue(Arrays.equals(a.secret,p.secret));}
    private static void assertEnrolled(JsonNode r){assertTrue(b(r,"success"));for(String k:List.of("publicIdentityOnly","authorizationExists","privateMode","noAliases","markerPublicOnly","markerAttempted"))assertTrue(b(r,k));}
    private static void assertReady(JsonNode s,int i){JsonNode a=agent(s,i);assertTrue(b(a,"ready"));assertTrue(b(a,"registered"));assertTrue(b(a,"socketOpen"));assertFalse(b(a,"isolated"));assertTrue(a.get("types").isArray() && a.get("types").size()>0);assertTrue(n(a,"generation")>0);}
    private static void assertAck(JsonNode r,String kind,String status,long version){assertTrue(b(r,"success"));assertEquals(kind,t(r,"kind"));assertEquals(status,t(r,"status"));assertEquals(version,n(r,"deliveryVersion"));}
    private static void assertDelivery(Lane f,int index,String status,long version){Map<String,Object> r=f.jdbc().queryForMap("SELECT status,version FROM agent_command_delivery WHERE id=?",index+1);assertEquals(status,r.get("status"));assertEquals(version,((Number)r.get("version")).longValue());}
    private static String t(JsonNode n,String key){return Ur06EnrollMysqlFixture.text(n,key);}
    private static long n(JsonNode n,String key){return Ur06EnrollMysqlFixture.integer(n,key);}
    private static boolean b(JsonNode n,String key){JsonNode v=n==null?null:n.get(key);if(v==null||!v.isBoolean())throw new SafeFailure("BOOLEAN_REQUIRED");return v.asBoolean();}
    private static JsonNode agent(JsonNode s,int i){return s.get("agents").get(i);}
    private static String randomSecret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    private static String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    // Original complete six E05 oracles, with the same strict field/type checks.
    private static String text(JsonNode value, String key) { return t(value,key); }
    private static long integer(JsonNode value, String key) { return n(value,key); }
    private static boolean bool(JsonNode value, String key) { return b(value,key); }
    private static String optional(JsonNode value, String key) { return Ur06EnrollMysqlFixture.optionalText(value,key); }
    private static List<JsonNode> array(JsonNode value, String key) {
        JsonNode list=value==null?null:value.get(key); if(list==null||!list.isArray())throw new SafeFailure("BUSINESS_ARRAY_REQUIRED");
        List<JsonNode> result=new ArrayList<>(); list.forEach(result::add); return result;
    }
    private static int children(JsonNode s, int index) { return array(agent(s, index), "children").size(); }
    private static boolean hasInbox(JsonNode s, int index, String state, boolean material) {
        return array(agent(s, index), "inbox").stream().anyMatch(r -> state.equals(optional(r, "state")) && (!material || optional(r, "materialDigest").matches("[0-9a-f]{64}")));
    }
    private static boolean hasTerminalFailure(JsonNode s, int index) {
        return array(agent(s, index), "ledger").stream().anyMatch(r -> Set.of("FAILED", "REJECTED").contains(optional(r, "status")));
    }
    private static List<JsonNode> http(JsonNode s) { return array(s, "http"); }
    private static String resultPath(JsonNode command) {
        return "/internal/agent/tasks/" + text(command, "taskId") + "/work-items/" + text(command, "workItemId") + "/reassignments/" + text(command, "reassignmentId") + "/commands/" + text(command, "commandId") + "/result-commit";
    }
    private static void assertCommittedBusiness(JsonNode s, int index, int expected) {
        assertEquals(expected, integer(s, "artifacts")); assertEquals(expected, integer(s, "submittedEvents"));
        JsonNode work = s.get("work").get(index); assertEquals("submitted", text(work, "status")); assertTrue(bool(work, "hasResult")); assertTrue(bool(work, "leaseCleared"));
        assertEquals(integer(s, "events"), integer(s, "eventVersion"));
    }
    private static void assertCommitted(JsonNode s, int index, int expected) {
        assertCommittedBusiness(s, index, expected); JsonNode delivery = s.get("deliveries").get(index);
        assertEquals("SUCCEEDED", text(delivery, "status")); assertEquals(10, integer(delivery, "version"));
    }
    private static void assertNoSucceeded(JsonNode s) { for (JsonNode d : array(s, "deliveries")) assertNotEquals("SUCCEEDED", text(d, "status")); }
    private static void assertD06Sequence(JsonNode s, int index) {
        List<JsonNode> acks = http(s).stream().filter(r -> "ACK".equals(optional(r, "category")) && integer(r, "status") == 200).toList();
        assertEquals(List.of("RECEIVED", "STARTED", "SUCCEEDED"), acks.stream().map(r -> text(r, "businessStatus")).toList());
        assertEquals(List.of(8L, 9L, 10L), acks.stream().map(r -> integer(r, "version")).toList());
        assertTrue(bool(acks.get(0), "firstAck")); assertEquals(8, integer(acks.get(1), "lastConfirmedVersion"));
        assertEquals(9, integer(acks.get(2), "lastConfirmedVersion"));
        List<JsonNode> leases = http(s).stream().filter(r -> Set.of("LEASE", "START", "HEARTBEAT").contains(optional(r, "category"))
            && "POST".equals(optional(r, "method")) && integer(r, "index") == index && integer(r, "status") == 200).toList();
        assertTrue(leases.size() >= 3); assertEquals("LEASE", text(leases.get(0), "category")); assertEquals("START", text(leases.get(1), "category"));
        assertEquals(5, integer(leases.get(0), "expectedVersion"));
        long confirmedVersion = integer(leases.get(0), "workVersion");
        for (int n = 1; n < leases.size(); n++) {
            JsonNode receipt = leases.get(n); assertTrue(bool(receipt, "actorMatches"));
            assertEquals(confirmedVersion, integer(receipt, "expectedVersion")); confirmedVersion = integer(receipt, "workVersion");
        }
        assertTrue(bool(leases.get(0), "actorMatches"));
        JsonNode resultPost = http(s).stream().filter(r -> "RESULT".equals(optional(r, "category"))
            && "POST".equals(optional(r, "method")) && integer(r, "index") == index && integer(r, "status") == 200).findFirst().orElseThrow();
        assertEquals(confirmedVersion, integer(resultPost, "expectedVersion"));
        List<JsonNode> heartbeats = http(s).stream().filter(r -> "HEARTBEAT".equals(optional(r, "category")) && integer(r, "index") == index && integer(r, "status") == 200).toList();
        assertFalse(heartbeats.isEmpty()); long last = integer(heartbeats.getLast(), "workVersion");
        assertEquals(last + 1, integer(s.get("work").get(index), "version"));
    }
    private static void assertRecoveryOnly(JsonNode s, boolean priorAck) {
        for (JsonNode r : http(s)) {
            assertFalse(Set.of("START", "HEARTBEAT").contains(optional(r, "category")));
            assertFalse("LEASE".equals(optional(r, "category")) && "POST".equals(optional(r, "method")));
            assertFalse("RESULT".equals(optional(r, "category")) && "POST".equals(optional(r, "method")));
            if ("RESULT".equals(optional(r, "category")) && "GET".equals(optional(r, "method"))) assertTrue(bool(r, "readOnly"));
        }
        if (priorAck) assertTrue(http(s).stream().anyMatch(r -> "ACK".equals(optional(r, "category")) && "PRIOR".equals(optional(r, "kind")) && integer(r, "version") == 10));
    }
    private static void assertUniqueCheckpoint(JsonNode s, int index, String command) {
        JsonNode a = agent(s, index); assertTrue(bool(a, "credentialsAbsent"));
        assertEquals(1, array(a, "ledger").stream().filter(e -> command.equals(text(e, "commandId"))).count());
        assertEquals(1, array(a, "inbox").stream().filter(e -> command.equals(text(e, "commandId"))).count());
        assertEquals(0, array(a, "pending").size());
    }

    private record Installation(String status,long version,Long consumed,Long issued,long expires,byte[] secret,byte[] authorization) {
        boolean same(Installation other){return status.equals(other.status)&&version==other.version&&Objects.equals(consumed,other.consumed)&&Objects.equals(issued,other.issued)&&expires==other.expires&&Arrays.equals(secret,other.secret)&&Arrays.equals(authorization,other.authorization);}
        boolean sameAuthorization(Installation other){return Objects.equals(consumed,other.consumed)&&Objects.equals(issued,other.issued)&&Arrays.equals(secret,other.secret)&&Arrays.equals(authorization,other.authorization);}
        @Override public String toString(){return "UR06_PRIVATE_INSTALLATION_REDACTED";}
    }
    private record Source(Path api,String commit,String tree,Path client,Path artifact,Path node,Path mysqld,Path codex){}
    private static String required(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new SafeFailure("SEVEN_INPUTS_REQUIRED");return v;}
    private static Path canonical(Path p)throws Exception{if(!p.isAbsolute()||!p.normalize().equals(p)||!p.toRealPath().equals(p))throw new SafeFailure("CANONICAL_INPUT_REQUIRED");return p;}
    private static String git(Path root,String...args)throws Exception{
        List<String> cmd=new ArrayList<>(List.of("/usr/bin/git","-C",root.toString()));cmd.addAll(List.of(args));ProcessBuilder b=new ProcessBuilder(cmd);b.environment().clear();b.environment().putAll(Map.of("PATH","/usr/bin:/bin","LANG","C.UTF-8"));
        Process p=b.start();byte[] bytes=p.getInputStream().readAllBytes();p.getErrorStream().readAllBytes();if(p.waitFor()!=0)throw new SafeFailure("GIT_PROOF_REQUIRED");return new String(bytes,StandardCharsets.UTF_8).strip();
    }
    private static Source source()throws Exception{
        Path api=canonical(Path.of(System.getProperty("ur06.api.root"))),client=canonical(Path.of(required("UR06_CLIENT_ROOT"))),artifact=canonical(Path.of(required("UR06_ARTIFACT_ROOT"))),node=canonical(Path.of(required("UR06_NODE_BIN"))),mysql=canonical(Path.of(required("UR06_MYSQLD_BIN"))),codex=canonical(Path.of(required("UR06_CODEX_BIN")));
        String commit=required("UR06_API_COMMIT");if(!commit.matches("[0-9a-f]{40}")||!CLIENT.equals(required("UR06_CLIENT_COMMIT"))||!commit.equals(git(api,"rev-parse","HEAD"))||!git(api,"status","--porcelain=v1","--untracked-files=all").isEmpty())throw new SafeFailure("EXACT_CLEAN_SOURCE_REQUIRED");
        git(api,"merge-base","--is-ancestor",BASELINE,commit);String tree=git(api,"rev-parse","HEAD^{tree}");if(!tree.matches("[0-9a-f]{40}")||!Files.isExecutable(node)||!Files.isExecutable(mysql)||!Files.isExecutable(codex))throw new SafeFailure("TOOLS_TREE_REQUIRED");
        return new Source(api,commit,tree,client,artifact,node,mysql,codex);
    }
    private final class Lane implements AutoCloseable {
        final Source source;final Path root;final String inode;final Path nodeScript;final List<Child> children=new ArrayList<>();
        final Map<Integer,String> secrets=new HashMap<>();final List<JsonNode> manifests=new ArrayList<>();
        MySql mysql;Ur06EnrollMysqlFixture.GuardedDataSource database;Child java,host,node;String origin;JsonNode provenance;
        final String purpose, initialFault; final List<String> authorizations=new ArrayList<>();
        final List<OwnedChild> owned=new ArrayList<>(); final Set<Long> nodeParents=new HashSet<>();
        final Set<Child> javaChildren=Collections.newSetFromMap(new IdentityHashMap<>());
        List<JsonNode> commands=new ArrayList<>(); JsonNode lastServer,lastNode,bootReady; boolean evidenceEmitted;
        Lane()throws Exception{this(null);}
        Lane(String fault)throws Exception{
            purpose=fault==null?"FOUNDATION":"M4"; initialFault=fault==null?"NONE":fault;
            if(!Set.of("NONE","RESULT_LOSS","ACK_LOSS","PREPARED","ROLLBACK").contains(initialFault))throw new SafeFailure("FAULT_ALLOWLIST_REQUIRED");
            source=source();root=Files.createTempDirectory("ur06-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();inode=fileKey(root);
            nodeScript=root.resolve("enroll-mysql-client.mjs");
            try {
            for(String name:List.of("home","tmp","mysql-data","mysql-files"))Files.createDirectory(root.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try(InputStream in=getClass().getResourceAsStream("/ur06/enroll-mysql-client.mjs")){if(in==null)throw new SafeFailure("NODE_RESOURCE_REQUIRED");Files.copy(in,nodeScript);}Files.setPosixFilePermissions(nodeScript,PosixFilePermissions.fromString("rw-------"));
                stage="SOURCE_ARTIFACT";Child prepare=nodeChild();Map<String,Object> r=base();r.put("op","PREPARE");
                if(purpose.equals("M4")) {
                    Path tracked=source.api.resolve("agent/jia-agent-service/src/ur04E05Http/resources/ur04/nonpaid-execution-child.mjs");
                    if(!tracked.toRealPath().equals(tracked)||Files.isSymbolicLink(tracked))throw new SafeFailure("TRACKED_CHILD_ALIAS_FORBIDDEN");
                    byte[] bytes=Files.readAllBytes(tracked);
                    if(!digest(bytes).equals("bd5c7a113efeeb5eabb00d2623065c62da3bab09f47bcedf825f3348e22c3805"))throw new SafeFailure("TRACKED_CHILD_HASH_REQUIRED");
                    MessageDigest blob=MessageDigest.getInstance("SHA-1");blob.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));blob.update(bytes);
                    if(!HexFormat.of().formatHex(blob.digest()).equals(git(source.api,"rev-parse","HEAD:agent/jia-agent-service/src/ur04E05Http/resources/ur04/nonpaid-execution-child.mjs")))throw new SafeFailure("TRACKED_CHILD_COMMIT_REQUIRED");
                    Path child=root.resolve("nonpaid-execution-child.mjs");Files.write(child,bytes);Files.setPosixFilePermissions(child,PosixFilePermissions.fromString("rw-------"));
                    r.put("childModule",child.toString());r.put("nodeBin",source.node.toString());
                    for(int i=0;i<3;i++)authorizations.add("rta1_"+randomSecret());
                }
                JsonNode ready=prepare.exchange(r,"NODE_PREPARED");prepare.exitZero();ready.get("manifests").forEach(manifests::add);assertEquals(purpose.equals("M4")?3:8,manifests.size());provenance=ready.get("provenance");
                stage="MYSQL_PRIVATE_BOOT";mysql=new MySql();database=new Ur06EnrollMysqlFixture.GuardedDataSource(mysql.spec);startJava(true);if(purpose.equals("M4")){startM4Node(true);awaitRegistration();}
            }catch(Throwable failure){close();throw failure;}
        }
        Map<String,Object> base(){Map<String,Object> m=new LinkedHashMap<>();m.put("root",root.toString());m.put("sourceRoot",source.client.toString());m.put("artifactRoot",source.artifact.toString());m.put("clientCommit",CLIENT);m.put("codexBin",source.codex.toString());m.put("purpose",purpose);return m;}
        ProcessBuilder isolated(List<String> command){ProcessBuilder b=new ProcessBuilder(command);b.directory(root.toFile());b.environment().clear();b.environment().putAll(Map.of("PATH",source.node.getParent()+":/usr/bin:/bin","HOME",root.resolve("home").toString(),"TMPDIR",root.resolve("tmp").toString(),"LANG","C.UTF-8","TZ","UTC"));return b;}
        Child nodeChild()throws Exception{ProcessBuilder b=isolated(List.of(source.node.toString(),nodeScript.toString()));b.environment().put("UR06_PRIVATE_CHILD","1");Child c=new Child(b.start());children.add(c);return c;}
        void startJava(boolean initialize)throws Exception {
            stage="ORIGINAL_JAVA_MYSQL_BOOT";String cp=System.getProperty("ur06.fixture.classpath");if(cp==null||cp.isBlank())throw new SafeFailure("CLASSPATH_REQUIRED");
            Path bin=Path.of(System.getProperty("java.home"),"bin/java").toRealPath();java=new Child(isolated(List.of(bin.toString(),"-Djava.io.tmpdir="+root.resolve("tmp"),"-Duser.home="+root.resolve("home"),"-Duser.timezone=UTC","-cp",cp,Ur06EnrollMysqlFixture.class.getName())).start());children.add(java);javaChildren.add(java);java.stopReceipt="STOPPED";
            assertEquals(java.process.pid(),n(java.receipt("JAVA_BOOT"),"pid"));
            Map<String,Object> input=new LinkedHashMap<>();input.putAll(Map.of("op","INIT","root",root.toString(),"apiRoot",source.api.toString(),"initialize",initialize,"database",mysql.spec.pipe(),"purpose",purpose,"fault",initialize?initialFault:"NONE"));
            if(purpose.equals("M4")&&initialize){input.put("manifests",manifests);input.put("authorizations",authorizations);}
            JsonNode ready=java.exchange(input,"JAVA_READY");bootReady=ready;if(purpose.equals("M4"))commands=array(ready,"commands");
            assertEquals(java.process.pid(),n(ready,"pid"));long port=n(ready,"port");if(port<=0||port>65535)throw new SafeFailure("PRIVATE_HTTP_PORT_REQUIRED");origin="http://127.0.0.1:"+port;
        }
        void restartJava()throws Exception{if(java.process.isAlive())throw new SafeFailure("JAVA_A_MUST_EXIT");startJava(false);}
        void create(int i,long expiry)throws Exception{stage="ORIGINAL_PENDING_CREATE";String secret=randomSecret();secrets.put(i,secret);JsonNode r=java.exchange(Map.of("op","CREATE","agentIndex",i,"manifest",manifests.get(i),"secretSha256",digest(secret.getBytes(StandardCharsets.UTF_8)),"expiresInMillis",expiry),"CREATED");assertTrue(b(r,"pending"));}
        Child enrollChild(int i,String state,String variant,String secret)throws Exception{return enrollChild(i,state,variant,secret,false);}
        Child enrollChild(int i,String state,String variant,String secret,boolean pause)throws Exception{stage="REAL_DEFAULT_FETCH_ENROLL";Child c=nodeChild();Map<String,Object> m=base();m.putAll(Map.of("op","ENROLL","apiOrigin",origin,"agentIndex",i,"stateName",state,"secret",secret,"pauseBeforeRequest",pause));if(variant!=null)m.put("variant",variant);c.send(m);return c;}
        JsonNode enroll(int i,String state,String variant,String secret)throws Exception{Child c=enrollChild(i,state,variant,secret);JsonNode r=c.receipt("ENROLLED");c.exitZero();return r;}
        void enrollPair()throws Exception{for(int i=0;i<2;i++){create(i,3_600_000);assertEnrolled(enroll(i,"state",null,secrets.get(i)));assertEquals(1,installation(i).version);}}
        void startHost()throws Exception{stage="REAL_ORIGINAL_WS_HOST";host=nodeChild();host.stopReceipt="NODE_STOPPED";Map<String,Object> m=base();m.put("op","HOST");m.put("apiOrigin",origin);assertEquals(host.process.pid(),n(host.exchange(m,"HOST_BOOT"),"pid"));}
        JsonNode hostSnapshot()throws Exception{return host.exchange(Map.of("op","SNAPSHOT"),"NODE_SNAPSHOT");}
        JsonNode waitHost(Predicate<JsonNode> condition)throws Exception{for(;;){JsonNode s=hostSnapshot();if(b(s,"failed"))throw new SafeFailure("ORIGINAL_HOST_FAILED");if(condition.test(s))return s;Thread.sleep(25);}}
        JsonNode awaitReady(int count)throws Exception{stage="REAL_MATCHED_REGISTRATION_READY";return waitHost(s->{for(int i=0;i<count;i++){if(b(agent(s,i),"isolated"))throw new SafeFailure("SUBJECT_ISOLATED_BEFORE_READY");if(!b(agent(s,i),"ready"))return false;}return true;});}
        JsonNode rawProbe(JsonNode headers)throws Exception{Child c=nodeChild();Map<String,Object> r=base();r.putAll(Map.of("op","WS_PROBE","apiOrigin",origin,"headers",headers));JsonNode result=c.exchange(r,"WS_PROBE");c.exitZero();return result;}
        JsonNode ack(int i,JsonNode command,String status,Long confirmed)throws Exception{Map<String,Object> r=new LinkedHashMap<>();r.put("op","ACK");r.put("agentIndex",i);r.put("command",command);r.put("status",status);r.put("deliveryVersion",confirmed);return host.exchange(r,"ACK_RESULT");}
        JdbcTemplate jdbc(){return new JdbcTemplate(database);}
        long deliveryCount(){return jdbc().queryForObject("SELECT COUNT(*) FROM agent_command_delivery",Long.class);}
        Installation installation(int i)throws Exception{
            // A NEW independent JDBC connection for every observation, never a Spring tx connection.
            try(Connection c=database.getConnection();PreparedStatement p=c.prepareStatement("SELECT status,version,enrollment_consumed_at,runtime_authorization_issued_at,enrollment_expires_at,enrollment_secret_hash,runtime_authorization_hash FROM agent_runtime_v1_installation WHERE installation_id=?")) {
                p.setString(1,Ur06EnrollMysqlFixture.INSTALLATIONS[i]);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SafeFailure("INSTALLATION_NOT_FOUND");return new Installation(r.getString(1),r.getLong(2),(Long)r.getObject(3),(Long)r.getObject(4),r.getLong(5),r.getBytes(6),r.getBytes(7));}
            }
        }
        void assertCredential(int i,String state,Installation row)throws Exception{
            Path path=root.resolve("agent-"+i).resolve(state).resolve("runtime-authorization.json");assertTrue(path.toRealPath().equals(path));assertFalse(Files.isSymbolicLink(path));assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(path));
            JsonNode privateData=Ur06EnrollMysqlFixture.JSON.readTree(Files.readAllBytes(path));assertEquals(2,privateData.size());assertTrue(Ur06EnrollMysqlFixture.INSTALLATIONS[i].equals(t(privateData,"installationId")));
            String token=t(privateData,"runtimeAuthorization");assertTrue(token.matches("rta1_[0-9a-f]{64}"));assertTrue(MessageDigest.isEqual(row.authorization,Ur06EnrollMysqlFixture.sha256(token)));
        }
        void assertRuntimeProof(int i,JsonNode headers)throws Exception {
            // Private proof is read only on the inherited parent pipe; neither its
            // token nor its digest is a test message/report/assertion operand.
            String authorization=t(headers,"Authorization");assertTrue(authorization.matches("AgentRuntime rts1_[0-9a-f]{64}"));
            Map<String,Object> row=jdbc().queryForMap("SELECT runtime_installation_id,runtime_host_id,runtime_instance_id,runtime_session_generation,token_hash FROM agent_runtime WHERE tenant_id='0' AND client_id=? AND agent_id=?",Ur06EnrollMysqlFixture.CLIENT,Ur06EnrollMysqlFixture.AGENTS[i]);
            assertTrue(Objects.equals(row.get("runtime_installation_id"),t(headers,"X-Agent-Installation-Id")));
            assertTrue(Objects.equals(row.get("runtime_host_id"),t(headers,"X-Agent-Host-Id")));
            assertTrue(Objects.equals(row.get("runtime_instance_id"),t(headers,"X-Agent-Runtime-Id")));
            assertTrue(((Number)row.get("runtime_session_generation")).longValue()==Long.parseLong(t(headers,"X-Agent-Session-Generation")));
            String verifier="urs1:"+digest(authorization.substring("AgentRuntime ".length()).getBytes(StandardCharsets.UTF_8))+":700:2";
            assertTrue(verifier.equals(row.get("token_hash"))); assertTrue(!authorization.contains(String.valueOf(row.get("token_hash"))));
        }
        List<JsonNode> http(String category,int index)throws Exception{List<JsonNode> rows=new ArrayList<>();java.exchange(Map.of("op","SNAPSHOT"),"SNAPSHOT").get("http").forEach(r->{JsonNode id=r.get("installationId");if(category.equals(t(r,"category"))&&id!=null&&Ur06EnrollMysqlFixture.INSTALLATIONS[index].equals(id.asText()))rows.add(r);});return rows;}
        void startM4Node(boolean fresh) throws Exception {
            assertEquals(java.process.pid(),integer(bootReady,"pid"));
            node=nodeChild();node.stopReceipt="NODE_STOPPED";nodeParents.add(node.process.pid());
            Map<String,Object> input=base();input.put("op","INIT");input.put("apiOrigin",origin);
            if(fresh)input.put("authorizations",authorizations);
            JsonNode boot=node.exchange(input,"NODE_BOOT");assertEquals(node.process.pid(),integer(boot,"pid"));
            assertEquals(CLIENT,text(boot,"sourceCommit"));
            if(!text(provenance,"sourceTree").matches("[0-9a-f]{40}"))throw new SafeFailure("SOURCE_TREE_REQUIRED");
            // PREPARE and each restart independently execute the single SOURCE verifier;
            // compare its proven tree instead of introducing a second source tuple.
            assertEquals(text(provenance,"sourceTree"),text(boot,"sourceTree"));
            for(String key:List.of("archiveSha256","sourceManifestSha256","artifactManifestSha256","nodeSha256","codexSha256")) {
                if(!text(boot,key).matches("[0-9a-f]{64}")||!text(boot,key).equals(text(provenance,key)))throw new SafeFailure("M4_SOURCE_TOOLCHAIN_READBACK_REQUIRED");
            }
            if(!text(boot,"cleanRun").matches("[A-Za-z0-9._:-]+")||!text(boot,"cleanRun").equals(text(provenance,"cleanRun")))throw new SafeFailure("PROVENANCE_RUN_REQUIRED");
            System.out.println("UR06_M4_NODE_BOOT pid="+node.process.pid()+" apiCommit="+source.commit+" clientCommit="+CLIENT+" syntheticNotProvider=true");
        }
        void awaitRegistration() throws Exception {
            stage = "REAL_NATIVE_THREE_REGISTRATIONS";
            boolean first = commands.isEmpty();
            waitNode(s -> {
                if (first && array(s, "agents").stream().anyMatch(a -> bool(a, "isolated")))
                    throw new SafeFailure("INITIAL_SUBJECT_ISOLATED");
                return array(s, "agents").stream().filter(a -> bool(a, "ready")).count() >= (first ? 3 : 2);
            });
            System.out.println("UR06_M4_PHASE NATIVE_REGISTERED initial=" + first);
        }
        JsonNode seed(int index) throws Exception {
            stage = "HISTORICAL_E05_SEED"; JsonNode receipt = java.exchange(Map.of("op", "SEED_WORK", "agentIndex", index), "WORK_SEEDED"); commands = array(receipt, "commands");
            return commands.stream().filter(c -> integer(c, "agentIndex") == index).findFirst().orElseThrow(() -> new SafeFailure("ORIGINAL_CODEC_COMMAND_REQUIRED"));
        }
        void dispatch(int index) throws Exception { stage = "REAL_WS_CODEC_DISPATCH"; assertTrue(bool(java.exchange(Map.of("op", "DISPATCH", "agentIndex", index), "DISPATCHED"), "sent")); }
        JsonNode nodeSnapshot() throws Exception { lastNode = node.exchange(Map.of("op", "SNAPSHOT"), "NODE_SNAPSHOT"); return lastNode; }
        JsonNode serverSnapshot() throws Exception { lastServer = java.exchange(Map.of("op", "SNAPSHOT"), "SNAPSHOT"); return lastServer; }
        JsonNode waitNode(Predicate<JsonNode> condition) throws Exception {
            for (;;) { JsonNode s = nodeSnapshot(); if (bool(s, "runtimeFailed")) throw new SafeFailure("ORIGINAL_RUNTIME_FAILED"); if (condition.test(s)) return s; Thread.sleep(25); }
        }
        void releaseAfterRealHeartbeat(int index) throws Exception {
            awaitRealHeartbeat(index);
            node.exchange(Map.of("op", "RELEASE", "agentIndex", index), "CHILD_RELEASED");
        }
        void awaitRealHeartbeat(int index) throws Exception {
            stage = "ORIGINAL_LEASE_START_CHILD_HEARTBEAT";
            JsonNode entered = waitNode(s -> {
                if (hasInbox(s, index, "recovery_required", false) || hasTerminalFailure(s, index))
                    throw new SafeFailure("ORIGINAL_EXECUTION_FAILED_BEFORE_CHILD");
                return children(s, index) == 1;
            }); trackChildren(entered);
            for (;;) {
                JsonNode s = serverSnapshot(); if (Ur06EnrollMysqlAcceptanceTest.http(s).stream().anyMatch(r -> "HEARTBEAT".equals(optional(r, "category")) && integer(r, "index") == index && integer(r, "status") == 200)) break;
                // No performance gate/deadline: require actual business heartbeat, or actual terminal failure.
                JsonNode n = nodeSnapshot(); if (hasInbox(n, index, "recovery_required", false)) throw new SafeFailure("REAL_HEARTBEAT_FAILED"); Thread.sleep(25);
            }
        }
        void terminal(int index) throws Exception {
            stage = "ORIGINAL_RESULT_AND_HTTP_D06";
            waitNode(s -> {
                if (hasTerminalFailure(s, index)) throw new SafeFailure("ORIGINAL_EXECUTION_TERMINAL_FAILURE");
                return array(agent(s, index), "confirmed").stream().anyMatch(c -> c.get("commit") != null && c.get("commit").isObject()
                    && "SUCCEEDED".equals(optional(c.get("commit"), "status")) && integer(c.get("commit"), "deliveryVersion") == 10)
                    && array(agent(s, index), "pending").isEmpty();
            });
            System.out.println("UR06_M4_PHASE TERMINAL_CONFIRMED agentIndex=" + index);
        }
        void mutate(int index, String mutation) throws Exception { java.exchange(Map.of("op", "MUTATE", "agentIndex", index, "mutation", mutation), "MUTATED"); }
        void restart() throws Exception {
            stage="INDEPENDENT_JAVA_NODE_RESTART_SAME_MYSQL_NO_RESEED";
            if(java.process.isAlive()||node.process.isAlive())throw new SafeFailure("BOTH_ORIGINAL_PROCESSES_MUST_EXIT");
            startJava(false);startM4Node(false);awaitRegistration();
        }
        void stopBoth() throws Exception { stage="OWNED_PROCESS_STOP";node.stop("NODE_STOPPED");java.stop("STOPPED"); }
        JsonNode persisted() throws Exception {
            stage="INDEPENDENT_NEW_GUARDED_JDBC_SAME_MYSQL";
            if(java.process.isAlive())throw new SafeFailure("PARENT_JDBC_ONLY_AFTER_CHILD_EXIT");
            // A fresh datasource/connection independent of BOTH JVM Spring transactions.
            var independent=new Ur06EnrollMysqlFixture.GuardedDataSource(mysql.spec);
            return Ur06EnrollMysqlFixture.JSON.valueToTree(Ur06EnrollMysqlFixture.snapshotM4(new JdbcTemplate(independent)));
        }
        void trackChildren(JsonNode snapshot) {
            for (JsonNode a : array(snapshot, "agents")) for (JsonNode c : array(a, "children")) {
                long pid = integer(c, "pid"), parentPid = integer(c, "parentPid");
                if (!nodeParents.contains(parentPid)) throw new SafeFailure("CHILD_OBSERVED_PARENT_REQUIRED");
                assertTrue(bool(c, "synthetic")); assertTrue(text(c, "cwdSha256").matches("[0-9a-f]{64}"));
                Set<String> allowed = Set.of("PATH", "LANG", "LC_ALL", "TZ", "TMPDIR", "HOME", "CODEX_HOME", "CYF_WORKSPACE_FILE_TOOLCHAIN_PYTHON", "CYF_WORKSPACE_FILE_DELIVERY_TOOL");
                for (JsonNode key : array(c, "environmentKeys")) { if (!key.isTextual() || !allowed.contains(key.asText())) throw new SafeFailure("CHILD_ENV_EXACT_ALLOWLIST_REQUIRED"); }
                ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(h -> {
                    // Historical receipts survive restart; never use an old PID receipt to adopt a new process.
                    var start = h.info().startInstant().orElseThrow(() -> new SafeFailure("CHILD_START_IDENTITY_REQUIRED"));
                    boolean known = owned.stream().anyMatch(o -> o.pid == pid && o.start.equals(start));
                    if (parentPid != node.process.pid()) {
                        if (known) throw new SafeFailure("PREVIOUS_OWNED_CHILD_STILL_ALIVE");
                        return; // recycled historical PID: not our process, do not control it
                    }
                    if (h.parent().map(ProcessHandle::pid).orElse(-1L) != parentPid) throw new SafeFailure("LIVE_CHILD_PARENT_REQUIRED");
                    if (!known) owned.add(new OwnedChild(pid, start));
                });
            }
        }
        void emitObservedEvidence() {
            if (evidenceEmitted) return;
            evidenceEmitted = true;
            // These are LAST_OBSERVED receipts, not a PASS label. JUnit terminal status is authoritative.
            Map<String, Object> receipt = new LinkedHashMap<>(); receipt.put("stage", "LAST_OBSERVED");
            receipt.put("apiCommit", source.commit()); receipt.put("apiTree", source.tree()); receipt.put("clientCommit", CLIENT);
            receipt.put("provenance", provenance); receipt.put("syntheticNotProvider", true);
            if (java != null) receipt.put("javaPid", java.process.pid()); if (node != null) receipt.put("nodePid", node.process.pid());
            if (lastServer != null) {
                for (String key : List.of("ackUpdates", "workUpdates", "artifactInserts", "submittedAttempts", "artifacts", "submittedEvents", "events", "eventVersion")) receipt.put(key, integer(lastServer, key));
                receipt.put("businessSha256", text(lastServer, "businessSha256")); receipt.put("http", Ur06EnrollMysqlAcceptanceTest.http(lastServer));
                receipt.put("work", array(lastServer, "work")); receipt.put("deliveries", array(lastServer, "deliveries"));
                receipt.put("generations", array(lastServer, "runtimes").stream().map(r -> integer(r, "generation")).toList());
            }
            if (lastNode != null) {
                List<Map<String, Object>> subjects = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    JsonNode a = agent(lastNode, i);
                    subjects.add(Map.of("agentIndex", i, "children", children(lastNode, i), "ready", bool(a, "ready"), "isolated", bool(a, "isolated"),
                        "credentialsAbsent", bool(a, "credentialsAbsent"), "inboxCount", array(a, "inbox").size(), "ledgerCount", array(a, "ledger").size(),
                        "pendingCount", array(a, "pending").size(), "conflictsCount", array(a, "conflicts").size()));
                }
                receipt.put("subjects", subjects);
            }
            System.out.println("UR06_M4_RECEIPT " + Ur06EnrollMysqlFixture.JSON.writeValueAsString(receipt));
        }
        void receipt(String phase,Map<String,Object> evidence)throws Exception {
            // Only source/toolchain hashes (never auth or enrollment digests), fixed counts/flags.
            Map<String,Object> out=new LinkedHashMap<>();out.put("phase",phase);out.put("apiCommit",source.commit);out.put("apiTree",source.tree);out.put("clientCommit",CLIENT);out.put("provenance",provenance);out.put("evidence",evidence);out.put("M4","NOT_RUN");out.put("production","NOT_RUN");out.put("provider","NOT_RUN");
            System.out.println("UR06_OBSERVED "+Ur06EnrollMysqlFixture.JSON.writeValueAsString(out));
        }
        @Override public void close()throws Exception {
            boolean failed=false;
            if(purpose.equals("M4"))try{emitObservedEvidence();}catch(Throwable ignored){System.out.println("UR06_M4_RECEIPT_EMISSION_FAILED");}
            // Read back borrowed full source/artifact before stopping/awaiting all
            // owned handles. Failure never prevents cleanup of those exact handles.
            if(provenance!=null)try {
                Child check=nodeChild();Map<String,Object> input=base();input.put("op","VERIFY");
                JsonNode readback=check.exchange(input,"VERIFIED");check.exitZero();
                assertTrue(provenance.equals(readback.get("provenance")));
                if(!source.commit.equals(git(source.api,"rev-parse","HEAD"))
                    || !source.tree.equals(git(source.api,"rev-parse","HEAD^{tree}"))
                    || !git(source.api,"status","--porcelain=v1","--untracked-files=all").isEmpty())throw new SafeFailure("FINAL_API_READBACK_REQUIRED");
            }catch(Throwable integrity){failed=true;}

            // Node first: abort/await original host, then Java, then owned MySQL. Attempt
            // all exact handles even on a failure; never clean borrowed source/artifact.
            for(Child c:children.reversed())if(!javaChildren.contains(c))try{c.close();}catch(Exception e){failed=true;}
            for(OwnedChild child:owned)try{child.close();}catch(Exception e){failed=true;}
            for(Child c:children.reversed())if(javaChildren.contains(c))try{c.close();}catch(Exception e){failed=true;}
            if(mysql!=null)try{mysql.close();}catch(Exception e){failed=true;}
            if(children.stream().anyMatch(c->c.process.isAlive())||owned.stream().anyMatch(OwnedChild::alive)||(mysql!=null&&mysql.process!=null&&mysql.process.isAlive()))throw new SafeFailure("OWNED_PROCESSES_NOT_STOPPED_NO_CLEANUP");
            if(!inode.equals(fileKey(root))||!root.toRealPath().equals(root))throw new SafeFailure("ROOT_IDENTITY_CHANGED_NO_CLEANUP");
            try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);} // no FOLLOW_LINKS
            if(failed)throw new SafeFailure("INTEGRITY_OR_OWNED_CLEANUP_FAILURE");
        }
        private final class MySql implements AutoCloseable {
            Process process,initialization,version;Ur06EnrollMysqlFixture.DatabaseSpec spec,admin;String dataInode;
            MySql()throws Exception{
                try {
                    // Read-only binary probe; --no-defaults remains first even for --version.
                    version=isolated(List.of(source.mysqld.toString(),"--no-defaults","--version")).start();byte[] bytes=version.getInputStream().readAllBytes();version.getErrorStream().readAllBytes();if(version.waitFor()!=0||!new String(bytes,StandardCharsets.UTF_8).matches("(?s).*\\bVer 8\\.0\\.21\\b.*"))throw new SafeFailure("MYSQL_BINARY_8021_REQUIRED");
                    int port;try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}if(port==3306||port==33060)throw new SafeFailure("MYSQL_RESERVED_PORT_FORBIDDEN");
                    Path data=root.resolve("mysql-data");dataInode=fileKey(data);try(var entries=Files.list(data)){assertTrue(entries.findAny().isEmpty());}
                    List<String> args=new ArrayList<>(List.of(source.mysqld.toString(),"--no-defaults","--basedir="+source.mysqld.getParent().getParent(),"--datadir="+data,"--socket="+root.resolve("mysql.sock"),"--pid-file="+root.resolve("mysql.pid"),"--log-error="+root.resolve("mysql.error"),"--bind-address=127.0.0.1","--port="+port,"--mysqlx=0","--general-log=OFF","--slow-query-log=OFF","--skip-log-bin","--secure-file-priv="+root.resolve("mysql-files"),"--user="+Files.getOwner(root).getName()));
                    rootGuard();List<String> init=new ArrayList<>(args);init.add("--initialize-insecure");initialization=isolated(init).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(initialization.waitFor()!=0)throw new SafeFailure("MYSQL_PRIVATE_INITIALIZE_FAILED");
                    rootGuard();process=isolated(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    String start=process.info().startInstant().orElseThrow(()->new SafeFailure("MYSQL_START_IDENTITY_REQUIRED")).toString();
                    admin=new Ur06EnrollMysqlFixture.DatabaseSpec(port,root,data,source.mysqld,process.pid(),start,inode,dataInode,"root","");
                    Connection rootConnection;
                    for(;;){
                        if(!process.isAlive())throw new SafeFailure("MYSQL_OWNED_BOOT_EXITED");
                        if(!Files.exists(root.resolve("mysql.pid"),LinkOption.NOFOLLOW_LINKS)){rootGuard();Thread.sleep(25);continue;}
                        admin.owned();
                        try {rootConnection=DriverManager.getConnection(admin.url("mysql"),"root","");}
                        catch(SQLException unavailable){if(unavailable.getSQLState()==null||!unavailable.getSQLState().startsWith("08"))throw unavailable;Thread.sleep(25);continue;}
                        try{admin.verify(rootConnection);break;}catch(Exception permanent){rootConnection.close();throw permanent;}
                    }
                    try(Connection c=rootConnection) {
                        String password=randomSecret(),adminPassword=randomSecret();
                        bootstrap(c,"CREATE DATABASE ur06_fixture CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
                        bootstrap(c,"CREATE USER 'ur06_fixture'@'127.0.0.1' IDENTIFIED BY '"+password+"'");
                        bootstrap(c,"GRANT ALL PRIVILEGES ON ur06_fixture.* TO 'ur06_fixture'@'127.0.0.1'");
                        bootstrap(c,"ALTER USER 'root'@'localhost' IDENTIFIED BY '"+adminPassword+"'");
                        spec=new Ur06EnrollMysqlFixture.DatabaseSpec(port,root,data,source.mysqld,process.pid(),start,inode,dataInode,"ur06_fixture",password);
                        admin=new Ur06EnrollMysqlFixture.DatabaseSpec(port,root,data,source.mysqld,process.pid(),start,inode,dataInode,"root",adminPassword);
                    }
                    try(Connection check=new Ur06EnrollMysqlFixture.GuardedDataSource(spec).getConnection()){spec.verify(check);}
                }catch(Throwable failure){close();throw failure;}
            }
            void rootGuard()throws Exception{if(!inode.equals(fileKey(root))||!dataInode.equals(fileKey(root.resolve("mysql-data")))||!root.toRealPath().equals(root))throw new SafeFailure("MYSQL_OWNED_ROOT_CHANGED");}
            void bootstrap(Connection c,String sql)throws Exception{admin.verify(c);try(Statement statement=c.createStatement()){statement.execute(sql);}}
            void awaitEnrollLockWait(Child left,Child right)throws Exception {
                try(Connection c=DriverManager.getConnection(admin.url("mysql"),admin.user(),admin.password())) {
                    for(;;) {
                        if(!left.process.isAlive()||!right.process.isAlive())throw new SafeFailure("CONCURRENT_ENROLL_EXITED_BEFORE_LOCK_WAIT");
                        admin.verify(c);
                        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT' AND trx_query LIKE '%agent_runtime_v1_installation%'")) {
                            assertTrue(r.next());if(r.getLong(1)>=2)return;
                        }
                        Thread.sleep(25);
                    }
                }
            }
            void awaitLockWait(Future<Integer> update)throws Exception{
                try(Connection c=DriverManager.getConnection(admin.url("mysql"),admin.user(),admin.password())){
                    for(;;){if(update.isDone()){update.get();throw new SafeFailure("EXPECTED_LOCK_WAIT_NOT_OBSERVED");}admin.verify(c);try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT'")){assertTrue(r.next());if(r.getLong(1)>0)return;}Thread.sleep(25);}
                }
            }
            @Override public void close()throws Exception {
                if(version!=null&&version.isAlive())stopOwned(version);
                if(initialization!=null&&initialization.isAlive()){rootGuard();stopOwned(initialization);}
                if(process!=null&&process.isAlive()){
                    rootGuard();
                    if(!process.info().command().map(Path::of).filter(source.mysqld::equals).isPresent()
                        || !Arrays.asList(process.info().arguments().orElse(new String[0])).contains("--datadir="+root.resolve("mysql-data")))throw new SafeFailure("MYSQL_STOP_IDENTITY_REQUIRED");
                    stopOwned(process);
                }
            }
        }
    }
    private static String fileKey(Path p)throws Exception{if(Files.isSymbolicLink(p))throw new SafeFailure("ROOT_ALIAS_FORBIDDEN");Object key=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();if(key==null)throw new SafeFailure("INODE_PROOF_REQUIRED");return key.toString();}
    private static void stopOwned(Process p)throws Exception {p.destroy();p.waitFor();} // exact Process object, no process search/age gate
    private record OwnedChild(long pid, java.time.Instant start) implements AutoCloseable {
        boolean alive() { return ProcessHandle.of(pid).filter(h->h.isAlive() && h.info().startInstant().filter(start::equals).isPresent()).isPresent(); }
        @Override public void close() {
            ProcessHandle.of(pid).filter(h -> h.isAlive() && h.info().startInstant().filter(start::equals).isPresent()).ifPresent(h -> {
                h.destroy(); while (h.isAlive()) { h.destroyForcibly(); try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SafeFailure("OWNED_CHILD_STOP_INTERRUPTED"); } }
            });
        }
    }
    private static final class Child implements AutoCloseable {
        final Process process;final String start;final BufferedWriter input;final BlockingQueue<JsonNode> queue=new LinkedBlockingQueue<>();final List<JsonNode> pending=new ArrayList<>();
        String stopReceipt;
        final ExecutorService drains=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"ur06-private-pipe");t.setDaemon(true);return t;});volatile boolean ended;
        Child(Process p){process=p;start=p.info().startInstant().orElseThrow(()->new SafeFailure("CHILD_IDENTITY_REQUIRED")).toString();input=new BufferedWriter(new OutputStreamWriter(p.getOutputStream(),StandardCharsets.UTF_8));
            drains.submit(()->{try(BufferedReader out=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=out.readLine())!=null)if(line.startsWith(Ur06EnrollMysqlFixture.PREFIX))queue.add(Ur06EnrollMysqlFixture.JSON.readTree(line.substring(Ur06EnrollMysqlFixture.PREFIX.length())));}catch(Exception ignored){}finally{ended=true;}});
            drains.submit(()->{try(InputStream error=p.getErrorStream()){error.transferTo(OutputStream.nullOutputStream());}catch(IOException ignored){}});
        }
        void send(Object request)throws Exception{input.write(Ur06EnrollMysqlFixture.JSON.writeValueAsString(request));input.newLine();input.flush();}
        JsonNode exchange(Object request,String expected)throws Exception{send(request);return receipt(expected);}
        JsonNode receipt(String expected)throws Exception{for(;;){for(var it=pending.iterator();it.hasNext();){JsonNode r=it.next();String stage=t(r,"stage");if(stage.equals("ERROR"))throw failure(r);if(stage.equals(expected)){it.remove();return r;}}JsonNode r=queue.poll(100,TimeUnit.MILLISECONDS);if(r!=null){if(!"HTTP_OBSERVED".equals(t(r,"stage")))pending.add(r);}else if(ended)throw new SafeFailure("CHILD_EXIT_BEFORE_"+expected);}}
        static SafeFailure failure(JsonNode r){String code=r.get("code")==null?"":r.get("code").asText();Set<String> allowed=Set.of("UR06_JAVA_FAILURE","UR06_NODE_FAILURE","ORIGINAL_RUNTIME_FAILURE","MYSQL_SERVER_PROOF_REQUIRED","MYSQL_PROCESS_IDENTITY_REQUIRED","REAL_TRANSACTION_PROXY_REQUIRED","NESTED_WIRE_REQUIRED","INSTALLATION_PRESEED_FORBIDDEN","HOSTED_CHECK_SET_DRIFT","PREPARE_OUTER_TRANSACTION_FORBIDDEN","CUT_REQUIRES_REAL_SUBMITTED_COMMIT","CUT_REQUIRES_REAL_D06_COMMIT","FAULT_ALLOWLIST_REQUIRED","PURPOSE_ALLOWLIST_REQUIRED","RESTART_RESEED_FORBIDDEN","WORK_RESEED_FORBIDDEN","DDL_ALIAS_FORBIDDEN","THREE_SYNTHETIC_INSTALLATIONS_REQUIRED","SYNTHETIC_SUBJECT_MISMATCH","SEALED_MANIFEST_REQUIRED");String result=allowed.contains(code)?code:"PRIVATE_CHILD_FAILED";
            String at=Ur06EnrollMysqlFixture.optionalText(r,"at");
            if(Set.of("BOOT_INPUT","BOOT_SCHEMA","BOOT_HTTP","BOOT_MYBATIS_SPRING","BOOT_TOMCAT_START","PRIVATE_INPUT","SOURCE_PROOF","ARTIFACT_PROOF","ORIGINAL_PAYLOAD_VALIDATE","PRIVATE_SUBJECT_PREPARE","ORIGINAL_HOST_CONFIG","ORIGINAL_HOST_RUNNING").contains(at))result+=" at="+at;
            if(r.get("sqlState")!=null&&r.get("sqlState").asText().matches("[A-Z0-9]{5}"))result+=" SQLSTATE="+r.get("sqlState").asText();
            if(r.get("sqlError")!=null&&r.get("sqlError").isIntegralNumber())result+=" errno="+r.get("sqlError").longValue();
            if(r.get("bean")!=null&&Set.of("sqlSessionFactory","runtimes","installations","agents","catalog","runtime","authentication","springSecurityFilterChain").contains(r.get("bean").asText()))result+=" bean="+r.get("bean").asText();return new SafeFailure(result);}
        void own(){if(process.isAlive()&&!process.info().startInstant().map(Object::toString).filter(start::equals).isPresent())throw new SafeFailure("CHILD_IDENTITY_CHANGED");}
        void stop(String expected)throws Exception{if(process.isAlive()){own();exchange(Map.of("op","STOP"),expected);exitZero();}}
        void exitZero()throws Exception{input.close();if(process.waitFor()!=0)throw new SafeFailure("CHILD_EXIT_NOT_ZERO");}
        void crash()throws Exception{
            own();List<OwnedChild> descendants=process.descendants().filter(ProcessHandle::isAlive)
                .map(h->new OwnedChild(h.pid(),h.info().startInstant().orElseThrow(()->new SafeFailure("OWNED_DESCENDANT_START_REQUIRED")))).toList();
            process.destroyForcibly();process.waitFor();boolean failed=false;
            for(OwnedChild child:descendants)try{child.close();}catch(Exception failure){failed=true;}
            if(failed)throw new SafeFailure("OWNED_DESCENDANT_CLEANUP_FAILED");
        }
        @Override public void close()throws Exception {
            try {
                if(process.isAlive()) {
                    if(stopReceipt!=null) {try{stop(stopReceipt);}catch(Exception failure){if(process.isAlive())crash();throw failure;}}
                    else crash();
                }
            } finally {
                try{input.close();}finally{drains.shutdown();while(!drains.awaitTermination(100,TimeUnit.MILLISECONDS)){/* every private pipe awaited */}}
            }
        }
    }
    private static final class SafeFailure extends RuntimeException {final String code;SafeFailure(String code){super(code,null,false,false);this.code=code;}}
}
