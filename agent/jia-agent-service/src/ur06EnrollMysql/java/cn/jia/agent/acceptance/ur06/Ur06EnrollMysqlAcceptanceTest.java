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
 * M4 genuine MySQL E05 remains NOT_RUN; passing these selectors cannot close UR06. */
class Ur06EnrollMysqlAcceptanceTest {
    private static final String BASELINE="fcac494a5103a7bc1175c96e9df50d2fcc35c182";
    private static final String CLIENT="7594fd72251d38b6e1d23a1a3cca184ae0d085e7";
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
        MySql mysql;Ur06EnrollMysqlFixture.GuardedDataSource database;Child java,host;String origin;JsonNode provenance;
        Lane()throws Exception{
            source=source();root=Files.createTempDirectory("ur06-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();inode=fileKey(root);
            nodeScript=root.resolve("enroll-mysql-client.mjs");
            try {
            for(String name:List.of("home","tmp","mysql-data","mysql-files"))Files.createDirectory(root.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try(InputStream in=getClass().getResourceAsStream("/ur06/enroll-mysql-client.mjs")){if(in==null)throw new SafeFailure("NODE_RESOURCE_REQUIRED");Files.copy(in,nodeScript);}Files.setPosixFilePermissions(nodeScript,PosixFilePermissions.fromString("rw-------"));
                stage="SOURCE_ARTIFACT";Child prepare=nodeChild();Map<String,Object> r=base();r.put("op","PREPARE");JsonNode ready=prepare.exchange(r,"NODE_PREPARED");prepare.exitZero();ready.get("manifests").forEach(manifests::add);assertEquals(8,manifests.size());provenance=ready.get("provenance");
                stage="MYSQL_PRIVATE_BOOT";mysql=new MySql();database=new Ur06EnrollMysqlFixture.GuardedDataSource(mysql.spec);startJava(true);
            }catch(Throwable failure){close();throw failure;}
        }
        Map<String,Object> base(){Map<String,Object> m=new LinkedHashMap<>();m.put("root",root.toString());m.put("sourceRoot",source.client.toString());m.put("artifactRoot",source.artifact.toString());m.put("clientCommit",CLIENT);m.put("codexBin",source.codex.toString());return m;}
        ProcessBuilder isolated(List<String> command){ProcessBuilder b=new ProcessBuilder(command);b.directory(root.toFile());b.environment().clear();b.environment().putAll(Map.of("PATH",source.node.getParent()+":/usr/bin:/bin","HOME",root.resolve("home").toString(),"TMPDIR",root.resolve("tmp").toString(),"LANG","C.UTF-8","TZ","UTC"));return b;}
        Child nodeChild()throws Exception{ProcessBuilder b=isolated(List.of(source.node.toString(),nodeScript.toString()));b.environment().put("UR06_PRIVATE_CHILD","1");Child c=new Child(b.start());children.add(c);return c;}
        void startJava(boolean initialize)throws Exception {
            stage="ORIGINAL_JAVA_MYSQL_BOOT";String cp=System.getProperty("ur06.fixture.classpath");if(cp==null||cp.isBlank())throw new SafeFailure("CLASSPATH_REQUIRED");
            Path bin=Path.of(System.getProperty("java.home"),"bin/java").toRealPath();java=new Child(isolated(List.of(bin.toString(),"-Djava.io.tmpdir="+root.resolve("tmp"),"-Duser.home="+root.resolve("home"),"-Duser.timezone=UTC","-cp",cp,Ur06EnrollMysqlFixture.class.getName())).start());children.add(java);java.stopReceipt="STOPPED";
            assertEquals(java.process.pid(),n(java.receipt("JAVA_BOOT"),"pid"));
            JsonNode ready=java.exchange(Map.of("op","INIT","root",root.toString(),"apiRoot",source.api.toString(),"initialize",initialize,"database",mysql.spec.pipe()),"JAVA_READY");
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
        void receipt(String phase,Map<String,Object> evidence)throws Exception {
            // Only source/toolchain hashes (never auth or enrollment digests), fixed counts/flags.
            Map<String,Object> out=new LinkedHashMap<>();out.put("phase",phase);out.put("apiCommit",source.commit);out.put("apiTree",source.tree);out.put("clientCommit",CLIENT);out.put("provenance",provenance);out.put("evidence",evidence);out.put("M4","NOT_RUN");out.put("production","NOT_RUN");out.put("provider","NOT_RUN");
            System.out.println("UR06_OBSERVED "+Ur06EnrollMysqlFixture.JSON.writeValueAsString(out));
        }
        @Override public void close()throws Exception {
            boolean failed=false;
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
            for(Child c:children.reversed())try{c.close();}catch(Exception e){failed=true;}
            if(mysql!=null)try{mysql.close();}catch(Exception e){failed=true;}
            if(children.stream().anyMatch(c->c.process.isAlive())||(mysql!=null&&mysql.process!=null&&mysql.process.isAlive()))throw new SafeFailure("OWNED_PROCESSES_NOT_STOPPED_NO_CLEANUP");
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
        JsonNode receipt(String expected)throws Exception{for(;;){for(var it=pending.iterator();it.hasNext();){JsonNode r=it.next();String stage=t(r,"stage");if(stage.equals("ERROR"))throw failure(r);if(stage.equals(expected)){it.remove();return r;}}JsonNode r=queue.poll(100,TimeUnit.MILLISECONDS);if(r!=null)pending.add(r);else if(ended)throw new SafeFailure("CHILD_EXIT_BEFORE_"+expected);}}
        static SafeFailure failure(JsonNode r){String code=r.get("code")==null?"":r.get("code").asText();Set<String> allowed=Set.of("UR06_JAVA_FAILURE","UR06_NODE_FAILURE","ORIGINAL_RUNTIME_FAILURE","MYSQL_SERVER_PROOF_REQUIRED","MYSQL_PROCESS_IDENTITY_REQUIRED","REAL_TRANSACTION_PROXY_REQUIRED","NESTED_WIRE_REQUIRED","INSTALLATION_PRESEED_FORBIDDEN","HOSTED_CHECK_SET_DRIFT");String result=allowed.contains(code)?code:"PRIVATE_CHILD_FAILED";
            if(r.get("sqlState")!=null&&r.get("sqlState").asText().matches("[A-Z0-9]{5}"))result+=" SQLSTATE="+r.get("sqlState").asText();
            if(r.get("sqlError")!=null&&r.get("sqlError").isIntegralNumber())result+=" errno="+r.get("sqlError").longValue();
            if(r.get("bean")!=null&&Set.of("sqlSessionFactory","runtimes","installations","agents","catalog","runtime","authentication","springSecurityFilterChain").contains(r.get("bean").asText()))result+=" bean="+r.get("bean").asText();return new SafeFailure(result);}
        void own(){if(process.isAlive()&&!process.info().startInstant().map(Object::toString).filter(start::equals).isPresent())throw new SafeFailure("CHILD_IDENTITY_CHANGED");}
        void stop(String expected)throws Exception{if(process.isAlive()){own();exchange(Map.of("op","STOP"),expected);exitZero();}}
        void exitZero()throws Exception{if(process.waitFor()!=0)throw new SafeFailure("CHILD_EXIT_NOT_ZERO");}
        void crash()throws Exception{own();process.destroyForcibly();process.waitFor();}
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
