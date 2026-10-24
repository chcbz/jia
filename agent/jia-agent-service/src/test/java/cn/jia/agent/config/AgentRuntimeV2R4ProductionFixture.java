package cn.jia.agent.config;

import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.agent.service.impl.AgentRuntimeV2EffectServiceImpl;
import cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator;
import cn.jia.agent.service.impl.*;
import cn.jia.agent.security.AgentRuntimeV2RecoveryAuthority;
import cn.jia.agent.mapper.AgentRuntimeV2EffectMapper;
import cn.jia.agent.skill.*;
import cn.jia.economy.config.*;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import cn.jia.economy.service.impl.EconomyPostingServiceImpl;
import cn.jia.core.tenant.*;
import org.mybatis.spring.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Fresh R4 support, not an old fixture/classpath adapter. Skill money and
 * resource rows use real production MyBatis mappers, REQUIRED transactions,
 * marketplace reducers and the real escrow posting primitive. External
 * credential/dispatch admission is an explicit trusted setup adapter only;
 * this fixture is NOT full WebSocket ACL, MySQL or activation evidence. */
public final class AgentRuntimeV2R4ProductionFixture {
    private AgentRuntimeV2R4ProductionFixture() { }
    /** Opt-in timing of the exact underlying calls. Diagnostic runs are never
     * acceptance: no queries, permissions, elapsed work or failures are hidden. */
    private static final class DiagnosticSql {
        boolean enabled;
        final Map<String,long[]> stages=new TreeMap<>();
        void clear(){stages.clear();}
        long start(){return enabled?System.nanoTime():0;}
        void end(String stage,long started){if(started==0)return;long elapsed=System.nanoTime()-started;var value=stages.computeIfAbsent(stage,ignored->new long[3]);value[0]++;value[1]=Math.addExact(value[1],elapsed);value[2]=Math.max(value[2],elapsed);}
        List<Map<String,Object>> snapshot(){var result=new ArrayList<Map<String,Object>>();stages.forEach((stage,value)->result.add(Map.of("stage",stage,"calls",value[0],"totalMillis",value[1]/1000000.0,"maxMillis",value[2]/1000000.0)));return List.copyOf(result);}
    }
    /** Instrument the SAME private H2 database beneath every JDBC/MyBatis
     * adapter. Failed mutation attempts count even when rolled back, without
     * relying on one JdbcTemplate's limited table-prefix counter. */
    private static final class AuditSqlDriver implements java.sql.Driver {
        private static final String PREFIX="jdbc:cyf-r4-audit:";
        private static final Map<String,java.util.concurrent.atomic.AtomicLong> COUNTS=new java.util.concurrent.ConcurrentHashMap<>();
        private static final Map<String,DiagnosticSql> DIAGNOSTICS=new java.util.concurrent.ConcurrentHashMap<>();
        static {try{java.sql.DriverManager.registerDriver(new AuditSqlDriver());}catch(java.sql.SQLException failure){throw new ExceptionInInitializerError(failure);}}
        static java.util.concurrent.atomic.AtomicLong instrument(DriverManagerDataSource source){String url=source.getUrl();var count=new java.util.concurrent.atomic.AtomicLong();COUNTS.put(url,count);DIAGNOSTICS.put(url,new DiagnosticSql());source.setUrl(PREFIX+url);return count;}
        static DiagnosticSql diagnostic(DriverManagerDataSource source){return Objects.requireNonNull(DIAGNOSTICS.get(source.getUrl().substring(PREFIX.length())));}
        public boolean acceptsURL(String url){return url!=null&&url.startsWith(PREFIX);}
        public Connection connect(String url,java.util.Properties info) throws java.sql.SQLException {
            if(!acceptsURL(url))return null;String original=url.substring(PREFIX.length());var count=Objects.requireNonNull(COUNTS.get(original));var diagnostic=Objects.requireNonNull(DIAGNOSTICS.get(original));long started=diagnostic.start();Connection actual;
            try{actual=java.sql.DriverManager.getConnection(original,info);}finally{diagnostic.end("connection.open",started);}
            return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                Object result=timed(actual,method,args,diagnostic,"connection.");
                if(result instanceof java.sql.Statement statement){String sql=args!=null&&args.length>0&&args[0] instanceof String value?value:null;Class<?> type=statement instanceof java.sql.CallableStatement?java.sql.CallableStatement.class:statement instanceof java.sql.PreparedStatement?java.sql.PreparedStatement.class:java.sql.Statement.class;
                    return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(ignored,operation,values)->{
                        String name=operation.getName(),query=sql!=null?sql:values!=null&&values.length>0&&values[0] instanceof String value?value:null;
                        if(name.startsWith("execute")&&(name.contains("Update")||name.contains("Batch")||query==null||!query.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT")))count.incrementAndGet();
                        Object returned=timed(statement,operation,values,diagnostic,"statement.");
                        if(returned instanceof java.sql.ResultSet rows&&diagnostic.enabled)return java.lang.reflect.Proxy.newProxyInstance(java.sql.ResultSet.class.getClassLoader(),new Class<?>[]{java.sql.ResultSet.class},(ignoredRows,read,readArgs)->timed(rows,read,readArgs,diagnostic,"resultset."));
                        return returned;
                    });
                }
                return result;
            });
        }
        private static Object timed(Object target,java.lang.reflect.Method method,Object[] args,DiagnosticSql diagnostic,String prefix) throws Throwable {if(!diagnostic.enabled)return call(target,method,args);long started=diagnostic.start();try{return call(target,method,args);}finally{diagnostic.end(prefix+method.getName(),started);}}
        private static Object call(Object target,java.lang.reflect.Method method,Object[] args) throws Throwable {try{return method.invoke(target,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}}
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url,java.util.Properties info){return new java.sql.DriverPropertyInfo[0];}
        public int getMajorVersion(){return 1;}public int getMinorVersion(){return 0;}public boolean jdbcCompliant(){return false;}
        public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getLogger("cn.jia.agent.r4-private-sql-audit");}
    }
    /** Actual TASK resource reducers/DAOs and private stores. Supporting task
     * roots are isolated setup rows, not a production catalog or WebSocket
     * authority proof. No artifact/archive/delivery implementation is mocked.
     * Temporary stores are retained so failed publication is inspectable. */
    static final class FileResource implements AutoCloseable {
        static final String AGENT="agt_00000000000000000000000000000001";
        final PersonalWorkspaceExecutionService.V2Scope scope=new PersonalWorkspaceExecutionService.V2Scope("0","caller-C","owner-a",AGENT,"task-a","item-a","exec-a","run-a");
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final PersonalWorkspaceExecutionServiceImpl workspace;
        final PersonalWorkspaceExecutionDaoImpl executions;
        final PersonalWorkspaceStorage storage;
        final AgentTaskArtifactService artifacts;
        final AgentTaskArtifactStorage artifactStorage;
        final AgentTaskFormalDeliveryService deliveries;
        final PersonalWorkspaceWriteService writes;
        final java.nio.file.Path storeRoot=java.nio.file.Files.createTempDirectory("cyf-r4-file-resource-");
        final byte[] output;
        private final Connection schemaConnection;
        private final AgentTaskEventAfterCommitPublisher notifications;
        private final boolean privateMysql;
        FileResource() throws Exception {this(new DriverManagerDataSource("jdbc:h2:mem:r4_file_"+UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000","sa",""));}
        private FileResource(DriverManagerDataSource ds) throws Exception {this(ds,false);}
        private FileResource(DriverManagerDataSource ds,boolean privateMysql) throws Exception {
            this.privateMysql=privateMysql;
            jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);schemaConnection=ds.getConnection();
            try{
                if(!privateMysql){
                for(String resource:List.of("db/agent-personal-workspace-v1.sql","db/agent-personal-workspace-v1_11-executions.sql","db/agent-personal-workspace-v1_13-task-execution.sql","db/agent-personal-workspace-v1_13-task-publication.sql","db/agent-task-formal-delivery-r2.sql","db/task-event-schema.sql"))localSchema(resource);
                // Exact production mappers perform all subsequent task reads,
                // locks, CAS and publication. This root setup is deliberately
                // not advertised as the production schema gate.
                jdbc.execute("CREATE TABLE agent_task_meta(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,reward_status VARCHAR(20) NOT NULL,assigned_agent_id VARCHAR(100),required_abilities TEXT,reward INT,assigned_at BIGINT,started_at BIGINT,completed_at BIGINT,failure_reason TEXT,collaboration_mode VARCHAR(20),risk_level VARCHAR(20),max_agents INT,coordinator_agent_id VARCHAR(100),review_required BOOLEAN,task_version BIGINT NOT NULL,current_event_version BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,create_time BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,owner_jiacn,task_id))");
                jdbc.execute("CREATE TABLE agent_task_member(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,agent_id VARCHAR(100) NOT NULL,member_role VARCHAR(20) NOT NULL,member_status VARCHAR(20) NOT NULL,assignment_source VARCHAR(20),joined_at BIGINT,accepted_at BIGINT,started_at BIGINT,completed_at BIGINT,last_heartbeat_at BIGINT,failure_reason TEXT,version BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,create_time BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,task_id,agent_id))");
                jdbc.execute("CREATE TABLE agent_task_work_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,work_item_id VARCHAR(100) NOT NULL,task_id VARCHAR(100) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,title VARCHAR(255) NOT NULL,description TEXT,work_type VARCHAR(30) NOT NULL,required_abilities TEXT,assignee_agent_id VARCHAR(100),status VARCHAR(20) NOT NULL,priority INT NOT NULL,required_item BOOLEAN NOT NULL,dependency_json TEXT,lease_token VARCHAR(100),lease_until BIGINT,attempt_count INT NOT NULL,max_attempts INT NOT NULL,result_artifact_id VARCHAR(100),submitted_at BIGINT,completed_at BIGINT,version BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,create_time BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,work_item_id))");
                jdbc.execute("CREATE TABLE agent_task_artifact(id BIGINT AUTO_INCREMENT PRIMARY KEY,artifact_id VARCHAR(100) NOT NULL,task_id VARCHAR(100) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,work_item_id VARCHAR(100),producer_agent_id VARCHAR(100) NOT NULL,artifact_type VARCHAR(30) NOT NULL,title VARCHAR(255) NOT NULL,content MEDIUMTEXT,storage_uri VARCHAR(1000),content_hash VARCHAR(128),artifact_version INT NOT NULL,visibility VARCHAR(20) NOT NULL,metadata_json TEXT,created_at BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,create_time BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,artifact_id,artifact_version))");
                }
                var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
                for(Class<?> mapper:List.of(PersonalWorkspaceExecutionMapper.class,PersonalWorkspaceExecutionInputMapper.class,PersonalWorkspaceExecutionOutputMapper.class,PersonalWorkspaceFileMapper.class,PersonalWorkspaceVersionMapper.class,PersonalWorkspaceOperationMapper.class,AgentTaskMetaMapper.class,AgentTaskMemberMapper.class,AgentTaskWorkItemMapper.class,AgentTaskArtifactMapper.class,AgentTaskFormalDeliveryMapper.class,AgentTaskEventMapper.class))config.addMapper(mapper);
                var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();global.setBanner(false);factory.setGlobalConfig(global);var session=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
                executions=new PersonalWorkspaceExecutionDaoImpl(session.getMapper(PersonalWorkspaceExecutionMapper.class),session.getMapper(PersonalWorkspaceExecutionInputMapper.class),session.getMapper(PersonalWorkspaceExecutionOutputMapper.class));
                var files=new PersonalWorkspaceDaoImpl(session.getMapper(PersonalWorkspaceFileMapper.class),session.getMapper(PersonalWorkspaceVersionMapper.class),session.getMapper(PersonalWorkspaceOperationMapper.class));
                var roots=new AgentTaskMetaDaoImpl();org.springframework.test.util.ReflectionTestUtils.setField(roots,"baseMapper",session.getMapper(AgentTaskMetaMapper.class));
                var members=new AgentTaskMemberDaoImpl(session.getMapper(AgentTaskMemberMapper.class));var items=new AgentTaskWorkItemDaoImpl(session.getMapper(AgentTaskWorkItemMapper.class));var artifactRows=new AgentTaskArtifactDaoImpl(session.getMapper(AgentTaskArtifactMapper.class));
                var eventRows=new AgentTaskEventDaoImpl();org.springframework.test.util.ReflectionTestUtils.setField(eventRows,"baseMapper",session.getMapper(AgentTaskEventMapper.class));
                notifications=new AgentTaskEventAfterCommitPublisher(mock(AgentTaskEventBroker.class),manager);var eventWriter=new AgentTaskEventWriterImpl(eventRows,manager,notifications);var mutations=new AgentTaskMutationTransactionImpl(roots,manager);
                var leases=new AgentWorkItemLeaseServiceImpl(members,items,mutations,eventWriter,900000L);var identities=mock(AgentIdentityService.class);when(identities.requireCanonicalAgentIdInScope("0","caller-C","owner-a",AGENT)).thenReturn(AGENT);when(identities.requirePersistedCanonicalAgentIdInScope("0","caller-C","owner-a",AGENT)).thenReturn(AGENT);leases.setAssigneeAuthority(new TaskAssigneeAuthorityServiceImpl(mock(cn.jia.agent.dao.AgentExternalServicePolicyDao.class),identities,mock(cn.jia.agent.dao.AgentTaskExecutionGrantDao.class)));
                artifactStorage=spy(new FileSystemAgentTaskArtifactStorage(storeRoot.resolve("artifacts"),1048576L,Set.of(PersonalWorkspaceExecutionProperties.DOCX,"application/json")));
                doAnswer(invocation->{requireOutsideStorage();return invocation.callRealMethod();}).when(artifactStorage).store(any(),any(),any());
                doAnswer(invocation->{requireOutsideStorage();return invocation.callRealMethod();}).when(artifactStorage).read(any(),any(),any(),anyLong(),any());
                artifacts=spy(new AgentTaskCollaborationServiceImpl(roots,members,items,mock(AgentTaskRequestDao.class),artifactRows,artifactStorage,mutations,eventWriter));
                deliveries=spy(new AgentTaskFormalDeliveryServiceImpl(leases,artifactRows,new AgentTaskFormalDeliveryDaoImpl(session.getMapper(AgentTaskFormalDeliveryMapper.class)),items,roots,mutations,eventWriter));
                storage=spy(new FileSystemPersonalWorkspaceStorage(storeRoot.resolve("workspace"),1048576L));
                doAnswer(invocation->{requireOutsideStorage();return invocation.callRealMethod();}).when(storage).store(any(),any(),any());
                doAnswer(invocation->{requireOutsideStorage();return invocation.callRealMethod();}).when(storage).read(any(),any(),any(),anyLong(),any());
                writes=spy(new PersonalWorkspaceWriteService(files,new PersonalWorkspaceOperationClaimTx(files)));
                workspace=spy(new PersonalWorkspaceExecutionServiceImpl(executions,files,mock(PersonalWorkspaceTaskLinkDao.class),mock(AgentRuntimeDao.class),storage,writes,new PersonalWorkspaceExecutionProperties(List.of(PersonalWorkspaceExecutionProperties.DOCX))));
                workspace.setTaskExecutionDependencies(mock(cn.jia.chat.service.WorkspaceConversationAccessService.class),items,leases);workspace.setTaskPublicationDependencies(artifacts,deliveries,mutations);
                long now=System.currentTimeMillis(),until=now+600000L;
                jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,reward_status,assigned_agent_id,collaboration_mode,risk_level,max_agents,review_required,task_version,current_event_version,tenant_id,client_id) VALUES('task-a','owner-a','running',?,'single','low',1,TRUE,4,0,'0','caller-C')",AGENT);
                jdbc.update("INSERT INTO agent_task_member(task_id,owner_jiacn,agent_id,member_role,member_status,assignment_source,version,tenant_id,client_id) VALUES('task-a','owner-a',?,'worker','working','manual',0,'0','caller-C')",AGENT);
                jdbc.update("INSERT INTO agent_task_work_item(work_item_id,task_id,owner_jiacn,title,work_type,assignee_agent_id,status,priority,required_item,lease_token,lease_until,attempt_count,max_attempts,version,tenant_id,client_id) VALUES('item-a','task-a','owner-a','Real delivery','document',?,'running',0,TRUE,'original-resource-lease',?,0,3,2,'0','caller-C')",AGENT,until);
                var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("exec-a").setOwnerJiacn("owner-a").setTaskId("task-a").setRunId("run-a").setExecutionMode("TASK").setWorkItemId("item-a").setLeaseToken("original-resource-lease").setLeaseWorkItemVersion(2L).setLeaseExpiresAt(until).setTargetAgentId(AGENT).setInstruction("Create the real document").setOutputContentMimeType(PersonalWorkspaceExecutionProperties.DOCX).setExecutionState("QUEUED").setGrantRevision(1L).setIdempotencyKey("r4-file-original").setRequestHash("a".repeat(64)).setCreatedAt(now);execution.setTenantId("0");execution.setClientId("caller-C");tx.executeWithoutResult(status->executions.insert(execution));
                try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var bytes=new java.io.ByteArrayOutputStream()){doc.createParagraph().createRun().setText("Actual R4 file publication");doc.write(bytes);output=bytes.toByteArray();}
            }catch(Exception failure){schemaConnection.close();throw failure;}
        }
        private void localSchema(String resource) throws Exception {
            String sql=new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$","").replaceAll("(?is)\\)\\s*ENGINE=InnoDB\\s+DEFAULT\\s+CHARSET=utf8mb4(?:\\s+COLLATE\\s*=\\s*[a-z0-9_]+)?(?:\\s+COMMENT='(?:''|[^'])*')?\\s*;", ");");
            for(String raw:sql.split(";")){
                String statement=raw.strip();if(statement.isEmpty())continue;
                if(!statement.startsWith("ALTER TABLE ")){schemaStatement(statement);continue;}
                int end=statement.indexOf('\n');String table=statement.substring("ALTER TABLE ".length(),end).strip();String body=statement.substring(end+1);var clauses=new ArrayList<String>();int start=0,depth=0;boolean quoted=false;
                for(int index=0;index<body.length();index++){char c=body.charAt(index);if(c=='\'')quoted=!quoted;else if(!quoted){if(c=='(')depth++;else if(c==')')depth--;else if(c==','&&depth==0){clauses.add(body.substring(start,index));start=index+1;}}}clauses.add(body.substring(start));
                for(String rawClause:clauses){String clause=rawClause.strip().replaceAll("\\s+AFTER\\s+[a-z0-9_]+","");if(clause.startsWith("ADD KEY "))schemaStatement("CREATE INDEX "+clause.substring(8).replaceFirst(" "," ON "+table+" "));else schemaStatement("ALTER TABLE "+table+" "+clause.replace("DROP CHECK ","DROP CONSTRAINT "));}
            }
        }
        private void schemaStatement(String sql) throws Exception {
            // H2 retains the DDL SessionLocal for constant-IN CHECK values.
            // Keep that exact session alive without relaxing real constraints.
            try(var statement=schemaConnection.createStatement()){statement.execute(sql);}
        }
        private static void requireOutsideStorage(){if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw new AssertionError("Real file/artifact storage I/O entered transaction");}
        AgentTaskArtifactPublishDTO artifactCommand(){var command=new AgentTaskArtifactPublishDTO();command.setArtifactId("pwe_art_exec-a");command.setWorkItemId("item-a");command.setProducerAgentId(AGENT);command.setArtifactType("document");command.setTitle("result.docx");command.setContentBytes(output);command.setContentMimeType(PersonalWorkspaceExecutionProperties.DOCX);command.setContentHash(HexFormat.of().formatHex(AgentRuntimeV2RegistrationFingerprints.sha256(output)));command.setContentByteLength((long)output.length);command.setArtifactVersion(1);command.setExpectedPreviousVersion(0);command.setVisibility("task_members");return command;}
        AgentRuntimeV2EffectCodec.FileCommit commitRequest(){String sha=HexFormat.of().formatHex(AgentRuntimeV2RegistrationFingerprints.sha256(output));String material="task-a\nrun-a\noutput_1\n"+sha+"\n"+output.length+"\n";String manifest="{\"outputs\":[{\"outputId\":\"output_1\",\"sha256\":\""+sha+"\",\"length\":"+output.length+"}]}";return new AgentRuntimeV2EffectCodec.FileCommit("exec-a","task-a","item-a","run-a","pwe_m_"+HexFormat.of().formatHex(AgentRuntimeV2RegistrationFingerprints.sha256(material.getBytes(StandardCharsets.UTF_8))),HexFormat.of().formatHex(AgentRuntimeV2RegistrationFingerprints.sha256(manifest.getBytes(StandardCharsets.UTF_8))));}
        byte[] pinnedInput() {
            byte[] content=new byte[65536];Arrays.fill(content,(byte)'x');var stored=storage.store(new PersonalWorkspaceStorage.Scope("0","caller-C","owner-a"),content,"text/plain");long now=System.currentTimeMillis();
            var file=new PersonalWorkspaceFileEntity().setFileId("input-file-a").setOwnerJiacn("owner-a").setSourceKind("UPLOAD").setOriginKind("USER_UPLOAD").setDisplayName("input.txt").setMediaFamily("document").setState("ACTIVE").setMetadataRevision(1L).setLatestVersion(1).setCreatedAt(now);file.setTenantId("0");file.setClientId("caller-C");
            var version=new PersonalWorkspaceVersionEntity().setFileId(file.getFileId()).setOwnerJiacn("owner-a").setVersion(1).setOriginalFilename("input.txt").setContentMimeType("text/plain").setByteLength(stored.byteLength()).setContentHash(stored.sha256()).setStorageUri(stored.storageUri()).setCreatedAt(now);version.setTenantId("0");version.setClientId("caller-C");
            var input=new PersonalWorkspaceExecutionInputEntity().setInputRef("input_1").setExecutionId("exec-a").setOwnerJiacn("owner-a").setFileId(file.getFileId()).setFileVersion(1).setOriginalFilename(version.getOriginalFilename()).setContentMimeType(version.getContentMimeType()).setByteLength(version.getByteLength()).setContentHash(version.getContentHash()).setStorageUri(version.getStorageUri()).setGrantState("ACTIVE").setCreatedAt(now);input.setTenantId("0");input.setClientId("caller-C");
            tx.executeWithoutResult(status->{var s=new PersonalWorkspaceWriteService.Scope("0","caller-C","owner-a");var claim=writes.claim(s,"CREATE","real-r4-input",stored.sha256());writes.completeCreate(s,claim.operation(),file,version);executions.insertInput(input);});return content;
        }
        int count(String table){if(!Set.of("agent_personal_workspace_file","agent_personal_workspace_file_version","agent_task_artifact","agent_task_formal_delivery","agent_task_formal_delivery_item","agent_task_event").contains(table))throw new IllegalArgumentException("fixed file fixture table");return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
        @Override public void close() throws Exception {try{notifications.close();if(!privateMysql)jdbc.execute("DROP ALL OBJECTS");}finally{schemaConnection.close();}}
    }
    /** Area-zero WORK_ITEM_EXECUTE admitted from a fresh setup graph through
     * real registration, dispatch and STARTED reducers. No accepted TASK_INVITE
     * origin, receipt, holder, wire or business row is rewritten/relabelled. */
    static final class CompositeFile implements AutoCloseable {
        final AgentRuntimeV2RegistrationTransactionTest.Fixture registration;
        final JdbcTemplate jdbc;
        final AgentRuntimeV2RegistrationContext identity;
        AgentRuntimeV2SocketTuple socket;
        final AgentRuntimeV2InternalOutboxDao dispatchRows;
        final AgentRuntimeV2ExecutionStore executionRows;
        final AgentRuntimeV2ExecutionEvidenceValidator executionEvidence;
        final AgentRuntimeV2EffectMapper effectRows;
        final AgentRuntimeV2HistoricalSettlementEvidenceValidator effectEvidence;
        final SkillInstallResultService skills=mock(SkillInstallResultService.class);
        final AgentRuntimeV2EffectServiceImpl reducer;
        final AgentRuntimeV2ExecutionServiceImpl execution;
        final FileResource file;
        final String runToken=UUID.randomUUID().toString();
        final java.util.concurrent.atomic.AtomicLong effectClockCalls=new java.util.concurrent.atomic.AtomicLong();
        final java.time.Clock effectClock;
        final cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl registrationService;
        final java.util.concurrent.atomic.AtomicLong sqlMutationCalls;
        private final DiagnosticSql diagnosticSql;
        void enableDiagnosticSql(){diagnosticSql.enabled=true;}
        void resetDiagnosticSql(){diagnosticSql.clear();}
        List<Map<String,Object>> diagnosticSqlTimings(){return diagnosticSql.snapshot();}
        private final Connection schemaConnection;
        CompositeFile() throws Exception {this(false);}
        CompositeFile(boolean advancingClock) throws Exception {this(advancingClock,false);}
        CompositeFile(boolean advancingClock,boolean realWallClock) throws Exception {this(advancingClock,realWallClock,true);}
        // Fault-injection Java tests keep their spies. The ordinary cold IPC
        // uses the identical production validators without test interception;
        // no validation, SQL read, lock, authority or deadline is skipped.
        CompositeFile(boolean advancingClock,boolean realWallClock,boolean evidenceSpies) throws Exception {this(advancingClock,realWallClock,evidenceSpies,null);}
        CompositeFile(boolean advancingClock,boolean realWallClock,boolean evidenceSpies,AgentRuntimeV2PrivateMysqlFixture nativeFixture) throws Exception {
            if(nativeFixture!=null&&(advancingClock||!realWallClock||evidenceSpies))throw new SecurityException("private native checkpoint requires ordinary real-clock FILE boot");
            registration=new AgentRuntimeV2RegistrationTransactionTest.Fixture(nativeFixture);jdbc=registration.jdbc;
            var f=registration;f.clock.value=System.currentTimeMillis();sqlMutationCalls=AuditSqlDriver.instrument(f.dataSource);diagnosticSql=AuditSqlDriver.diagnostic(f.dataSource);schemaConnection=f.dataSource.getConnection();
            effectClock=realWallClock?java.time.Clock.systemUTC():advancingClock?new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}public java.time.Instant instant(){return java.time.Instant.ofEpochMilli(millis());}public long millis(){effectClockCalls.incrementAndGet();return ++f.clock.value;}}:f.clock;
            var runtimeClock=realWallClock?effectClock:f.clock;var runtimeResolver=realWallClock?new cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver(f.db,f.keys,runtimeClock):f.resolver;
            registrationService=realWallClock?new cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl(f.db,runtimeResolver,f.keys,runtimeClock):f.service;
            try{
                // Current ACL setup has no registration/dispatch fact yet.
                for(String table:List.of("core_tenant_control","core_tenant_module_control","oauth_client_tenant_grant","oauth_client_tenant_domain_grant","oauth_api_key","agent_runtime","agent_identity_registry","agent_persona_binding","agent_runtime_v2_registration_authority","core_deployment_tenant_grant","core_deployment_module_admission"))jdbc.update("UPDATE "+table+" SET tenant_id='0'");
                for(String table:List.of("agent_runtime","agent_persona_binding","agent_runtime_v2_registration_authority"))jdbc.update("UPDATE "+table+" SET agent_id=?",FileResource.AGENT);jdbc.update("UPDATE agent_identity_registry SET canonical_agent_id=?",FileResource.AGENT);
                identity=runtimeResolver.proof("api-key-1",FileResource.AGENT,AgentRuntimeV2RegistrationTransactionTest.SECRET);
                var worker=new WorkerScope(TenantId.of("0"),DataDomainId.of("cyf"),"local-experiment-a","local-api-a",4,11,7,"agent-command",3);var proof=AgentRuntimeV2CompositeWorkerProof.from(worker,"b".repeat(64),"c".repeat(64));
                if(!f.privateMysql)jdbc.execute("ALTER TABLE core_deployment_registration ADD credential_digest VARCHAR(64)");jdbc.update("UPDATE core_deployment_registration SET credential_digest=?",proof.credentialSha256());
                f.insert("oauth_client",cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("client_id","caller-C"));f.insert("oauth_client_tenant_grant",cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("tenant_id","0","client_id","caller-C","status","ACTIVE","grant_revision",8L));f.insert("oauth_client_tenant_domain_grant",cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("tenant_id","0","client_id","caller-C","data_domain_id","cyf","status","ACTIVE","domain_revision",3L));
                jdbc.update("INSERT INTO core_deployment_registration SELECT 'producer-a',status,stage,artifact_digest,worker_generation,registration_revision,credential_digest FROM core_deployment_registration WHERE deployment_id='local-api-a'");jdbc.update("INSERT INTO core_deployment_tenant_grant SELECT 'producer-a',tenant_id,data_domain_id,status,execution_pool_id,grant_revision,allow_read,allow_write FROM core_deployment_tenant_grant WHERE deployment_id='local-api-a'");jdbc.update("INSERT INTO core_deployment_module_admission SELECT 'producer-a',tenant_id,data_domain_id,module_id,mode_state,mode_revision,migration_epoch,admission_epoch,schema_version,registration_revision,grant_revision,allow_read,allow_write,status FROM core_deployment_module_admission WHERE deployment_id='local-api-a'");
                var created=registrationService.create(identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,f.createWire(UUID.randomUUID().toString()));socket=Objects.requireNonNull(created.currentSocket(),"Fresh area-zero registration");
                long now=runtimeClock.millis(),expiry=now+AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS;String intent="r4-file-execute";
                var draft=new AgentCommandDraft(1,AgentCommandCanonicalCodec.hallCommandId("0","caller-C","owner-a","task-a",FileResource.AGENT,intent,"WORK_ITEM_EXECUTE"),"task-a",intent,"0","caller-C","owner-a","task-a","item-a",FileResource.AGENT,"WORK_ITEM_EXECUTE",now,expiry,intent,new AgentHallCommandPayload("work_item_execute","Create the real document","juyiting","r4 real file fixture",null,null,"supervised",true,new AgentHallCommandContext("Real delivery","Real document",null,null,"1",List.of(),List.of())));
                byte[] business=AgentCommandCanonicalCodec.businessBytes(draft),wire=AgentCommandCanonicalCodec.wireBytesV2(draft,"msg-1",41L,1,new ProducerScope(TenantId.of("0"),DataDomainId.of("cyf"),"local-experiment-a","producer-a",7,"agent-command",3));
                var token=new AgentInboxClaimToken(61,"agent-command-dispatch-v2","0","cyf","local-experiment-a",7,"producer-a","agent-command",3,2,"local-api-a",4,"caller-C","msg-1","evt-1",draft.commandId(),"task-a",FileResource.AGENT,"WORK_ITEM_EXECUTE",41,"r4-file-broker-claim",now+20000,1,0,1,6,expiry);
                var common=cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("protocol_version",2,"tenant_id","0","data_domain_id","cyf","execution_pool_id","local-experiment-a","admission_epoch",7L,"producer_deployment_id","producer-a","module_id","agent-command","migration_epoch",3L,"client_id","caller-C","command_id",draft.commandId(),"expires_at",expiry,"attempt_count",1,"active_attempt",1,"version",6L,"next_retry_at",null,"lease_owner",null,"lease_until",null,"last_error",null,"claimed_deployment_id",null,"claimed_worker_generation",null,"replay_parent_message_id",null,"replay_requester_id",null,"replay_approver_id",null,"replay_reason",null,"create_time",now,"update_time",now);
                var delivery=new LinkedHashMap<>(common);delivery.putAll(cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("id",41L,"owner_jiacn","owner-a","task_id","task-a","work_item_id","item-a","target_agent_id",FileResource.AGENT,"command_type","WORK_ITEM_EXECUTE","command_payload",business,"command_payload_hash",AgentRuntimeV2RegistrationFingerprints.sha256(business),"status","CONSUMED","active_message_id","msg-1"));transportTable("agent_command_delivery",delivery);
                var outbox=new LinkedHashMap<>(common);outbox.putAll(cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("id",51L,"event_id","evt-1","message_id","msg-1","delivery_id",41L,"aggregate_type","TASK","aggregate_id","task-a","destination","dispatch-exchange","routing_key","dispatch-key","wire_payload",wire,"wire_payload_hash",AgentRuntimeV2RegistrationFingerprints.sha256(wire),"status","PUBLISHED","publisher_confirm_status","ACK","confirmed_at",now,"confirm_error",null,"mandatory_return_status","NOT_RETURNED","returned_at",null,"return_reply_code",null,"return_reply_text",null,"published_at",now));transportTable("agent_outbox_event",outbox);
                if(!f.privateMysql)jdbc.execute("DROP TABLE agent_consumer_inbox");var inbox=new LinkedHashMap<>(common);inbox.remove("claimed_deployment_id");inbox.remove("claimed_worker_generation");inbox.putAll(cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("id",61L,"claimant_deployment_id","local-api-a","claimant_worker_generation",4L,"consumer_name",token.consumerName(),"message_id","msg-1","event_id","evt-1","delivery_id",41L,"wire_payload",wire,"wire_payload_hash",AgentRuntimeV2RegistrationFingerprints.sha256(wire),"status","PROCESSING","result_status",null,"processed_at",null,"lease_owner",token.leaseOwner(),"lease_until",token.leaseUntil(),"version",0L,"claim_delivery_version",6L,"claim_inbox_version",0L,"claim_started_at",now,"evidence_provenance","R2B1_ENFORCED"));
                for(String name:List.of("runtime_dispatch_id","runtime_binding_id","runtime_binding_generation","dispatch_runtime_instance_id","dispatch_runtime_credential_client_id","dispatch_receiver_registration_revision","dispatch_generation_fact_id","dispatch_generation_snapshot_sha256","execution_origin_snapshot_sha256","dispatch_prepared_at","ack_delivery_version","ack_message_id","ack_status","ack_at","ack_agent_id","ack_runtime_installation_id","ack_runtime_instance_id","ack_runtime_binding_id","ack_runtime_binding_generation","ack_committed_at","execution_started_at","execution_lease_until","execution_lease_revision","execution_holder_binding_id","execution_holder_binding_generation","execution_holder_runtime_instance_id","execution_current_lease_fact_id"))inbox.put(name,null);transportTable("agent_consumer_inbox",inbox);
                if(!f.privateMysql){String dispatchSql=new ClassPathResource("db/agent-runtime-v2-dispatch-manual-20261001.sql").getContentAsString(StandardCharsets.UTF_8);dispatchSql=dispatchSql.substring(0,dispatchSql.lastIndexOf(" ENGINE="))+";";new ResourceDatabasePopulator(new ByteArrayResource(dispatchSql.replace("[[:cntrl:]]","\\p{Cntrl}").getBytes(StandardCharsets.UTF_8))).populate(schemaConnection);}
                createExecutionSchema();var manager=new DataSourceTransactionManager(f.dataSource);executionRows=new AgentRuntimeV2ExecutionStoreImpl(new AgentRuntimeV2ExecutionMapper(jdbc));var executionValidator=new AgentRuntimeV2ExecutionEvidenceValidator(executionRows);executionEvidence=evidenceSpies?spy(executionValidator):executionValidator;dispatchRows=new AgentRuntimeV2InternalOutboxDaoImpl(new AgentRuntimeV2InternalOutboxMapper(jdbc));var composite=new cn.jia.agent.security.AgentRuntimeV2CompositeAuthority(f.db,f.keys,runtimeClock);var dispatch=new AgentRuntimeV2DispatchServiceImpl(dispatchRows,composite,proof,manager,runtimeClock,new AgentRuntimeV2DispatchSource(dispatchRows,executionEvidence),failure->{throw new AssertionError("Fresh real file dispatch",failure);});
                if(dispatch.prepare(new AgentCommandV2ExecutionPort.Command(token,"owner-a",wire)).kind()!=AgentCommandV2ExecutionPort.Kind.DISPATCHED)throw new IllegalStateException("Fresh real file dispatch rejected");
                var authority=new cn.jia.agent.security.AgentRuntimeV2ExecutionAuthority(f.db,f.keys,runtimeClock);execution=new AgentRuntimeV2ExecutionServiceImpl(executionRows,authority,executionEvidence,manager,runtimeClock);
                for(String status:List.of("RECEIVED","STARTED"))if(execution.acknowledge(identity,socket,AgentRuntimeV2ExecutionCodec.encodeAck(new AgentRuntimeV2ExecutionCodec.Ack("ack-"+status,origin().text("runtime_dispatch_id"),"msg-1",status,now))).close())throw new IllegalStateException("Fresh real file "+status+" rejected");
                if(!f.privateMysql)new ResourceDatabasePopulator(new ByteArrayResource(AgentRuntimeV2EffectConfiguration.manualSql().replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","").getBytes(StandardCharsets.UTF_8))).populate(schemaConnection);
                file=new FileResource(f.dataSource,f.privateMysql);effectRows=new AgentRuntimeV2EffectMapper(jdbc);var effectValidator=new AgentRuntimeV2HistoricalSettlementEvidenceValidator(effectRows);effectEvidence=evidenceSpies?spy(effectValidator):effectValidator;reducer=new AgentRuntimeV2EffectServiceImpl(executionRows,effectRows,authority,new AgentRuntimeV2RecoveryAuthority(f.db,f.keys,effectClock),executionEvidence,effectEvidence,file.workspace,skills,manager,effectClock);
                if(f.privateMysql)AgentRuntimeV2PrivateMysqlFixture.verifyCatalogAndGraphs(jdbc);
            }catch(Exception|AssertionError failure){schemaConnection.close();throw failure;}
        }
        private void transportTable(String table,Map<String,Object> values){if(registration.privateMysql){registration.insert(table,values);return;}var columns=new ArrayList<String>();values.forEach((key,value)->columns.add(key+" "+(value instanceof byte[]||key.endsWith("sha256")||key.endsWith("_hash")||key.equals("wire_payload")||key.equals("command_payload")?"VARBINARY(175018)":value instanceof Number||key.endsWith("_at")||key.endsWith("_until")||key.endsWith("_version")||key.endsWith("_generation")||key.endsWith("_revision")?"BIGINT":"VARCHAR(1000)")));jdbc.execute("CREATE TABLE "+table+"("+String.join(",",columns)+",PRIMARY KEY(id))");registration.insert(table,values);}
        private void createExecutionSchema(){
            if(registration.privateMysql)return;
            for(String table:List.of(AgentRuntimeV2ExecutionStore.RECEIPT,AgentRuntimeV2ExecutionStore.FACT,AgentRuntimeV2ExecutionStore.GRANT,AgentRuntimeV2ExecutionStore.REQUEST))jdbc.execute("DROP TABLE "+table);
            for(String table:List.of(AgentRuntimeV2ExecutionStore.RECEIPT,AgentRuntimeV2ExecutionStore.FACT,AgentRuntimeV2ExecutionStore.GRANT,AgentRuntimeV2ExecutionStore.REQUEST,AgentRuntimeV2ExecutionStore.ANCHOR)){
                var columns=new ArrayList<String>();for(var column:AgentRuntimeV2PersistenceSchemaGate.executionExtendedColumns().get(table))columns.add(column.name()+" "+column.columnType()+(column.extra().equals("auto_increment")?" GENERATED BY DEFAULT AS IDENTITY":column.nullable()?"":" NOT NULL"));columns.add("PRIMARY KEY(id)");jdbc.execute("CREATE TABLE "+table+"("+String.join(",",columns)+")");
                AgentRuntimeV2PersistenceSchemaGate.registrationExtendedIndexes().get(table).forEach((name,index)->{if(!name.equals("PRIMARY"))jdbc.execute("CREATE "+(index.unique()?"UNIQUE ":"")+"INDEX r4_"+UUID.randomUUID().toString().replace("-","")+" ON "+table+"("+String.join(",",index.columns())+")");});
                var checks=new LinkedHashMap<String,String>();AgentRuntimeV2PersistenceSchemaGate.expectedCheckClauses().forEach((name,clause)->{if(name.startsWith(table.equals(AgentRuntimeV2ExecutionStore.RECEIPT)?"chk_ack_receipt":table.equals(AgentRuntimeV2ExecutionStore.FACT)?"chk_execution_lease_fact":table.equals(AgentRuntimeV2ExecutionStore.GRANT)?"chk_execution_grant":table.equals(AgentRuntimeV2ExecutionStore.ANCHOR)?"chk_execution_continuation":"chk_execution_request"))checks.put(name,clause);});checks.putAll(AgentRuntimeV2RegistrationSchemaGate.expectedChecks().get(table));if(table.equals(AgentRuntimeV2ExecutionStore.REQUEST))checks.put("chk_execution_request_raw",AgentRuntimeV2ExecutionSchemaGate.checkClause());checks.forEach((name,clause)->jdbc.execute("ALTER TABLE "+table+" ADD CONSTRAINT "+name+" CHECK("+AgentRuntimeV2ExecutionTransactionTest.Fixture.localCheck(clause)+")"));
            }
        }
        AgentRuntimeV2InternalOutboxRow origin(){return dispatchRows.forInboxHint(61);}
        AgentRuntimeV2EffectService.TrustedRoute route(){return new AgentRuntimeV2EffectService.TrustedRoute(socket.sessionFence(),socket.socketOwnerInstanceId(),socket.socketOwnerEpoch());}
        void reconnectForAuditRead(){
            var previous=AgentRuntimeV2RegistrationCodec.decodeReceipt(jdbc.queryForObject("SELECT receipt_wire_bytes FROM agent_runtime_v2_registration_receipt WHERE registration_receipt_id=(SELECT current_registration_receipt_id FROM agent_runtime_v2_binding WHERE runtime_binding_id=?)",byte[].class,socket.runtimeBindingId()));
            String fence=UUID.randomUUID().toString();long epoch=socket.socketOwnerEpoch();
            var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(registration.service.challenge(identity,fence,AgentRuntimeV2RegistrationTransactionTest.OWNER,epoch,registration.challengeWire(previous,UUID.randomUUID().toString())).responseBytes());
            var result=registration.service.reconnect(identity,fence,AgentRuntimeV2RegistrationTransactionTest.OWNER,epoch,registration.commitWire(previous,challenge,UUID.randomUUID().toString()));
            socket=Objects.requireNonNull(result.currentSocket(),"Real current audit registration");
        }
        AgentRuntimeV2EffectCodec.EffectRequest request(AgentRuntimeV2EffectCodec.Kind kind,AgentRuntimeV2EffectCodec.Phase phase,AgentRuntimeV2EffectCodec.Resource resource,byte[] content){var started=jdbc.queryForMap("SELECT * FROM agent_command_v2_ack_receipt WHERE status='STARTED'");var grant=jdbc.queryForMap("SELECT * FROM agent_command_v2_execution_grant");var reference=new AgentRuntimeV2EffectCodec.GrantReference(UUID.randomUUID().toString(),origin().text("runtime_dispatch_id"),(String)started.get("receipt_id"),HexFormat.of().formatHex((byte[])started.get("receipt_wire_sha256")),(String)grant.get("execution_grant_id"),HexFormat.of().formatHex((byte[])grant.get("grant_wire_sha256")));String effectId=AgentRuntimeV2EffectCodec.effectId(AgentRuntimeV2HistoricalSettlementEvidenceValidator.stableKey(origin()),HexFormat.of().formatHex(AgentRuntimeV2RegistrationFingerprints.sha256((byte[])jdbc.queryForMap("SELECT * FROM agent_command_delivery").get("command_payload"))),origin().text("runtime_dispatch_id"),(String)grant.get("original_start_lease_fact_id"),runToken,kind,phase,resource);return new AgentRuntimeV2EffectCodec.EffectRequest(reference,runToken,effectId,kind,phase,resource,content);}
        AgentRuntimeV2EffectService.Result reduce(AgentRuntimeV2EffectCodec.EffectRequest request){return reducer.effect(identity,socket,route(),AgentRuntimeV2EffectCodec.encode(request));}
        AgentRuntimeV2EffectService.Result reconcile(AgentRuntimeV2EffectCodec.Reconcile request){return reducer.reconcile(identity,null,new AgentRuntimeV2EffectService.TrustedRoute("r4-audit-fence","r4-audit-owner",0),AgentRuntimeV2EffectCodec.encode(request));}
        AgentRuntimeV2EffectCodec.Reconcile terminal(String status){byte[] ack=AgentRuntimeV2ExecutionCodec.encodeAck(new AgentRuntimeV2ExecutionCodec.Ack(UUID.randomUUID().toString(),origin().text("runtime_dispatch_id"),"msg-1",status,registration.clock.value));return new AgentRuntimeV2EffectCodec.Reconcile(UUID.randomUUID().toString(),origin().text("runtime_dispatch_id"),AgentRuntimeV2EffectCodec.Subject.EXECUTION,origin().text("runtime_dispatch_id"),ack,null);}
        byte[] renewal(){var started=jdbc.queryForMap("SELECT * FROM agent_command_v2_ack_receipt WHERE status='STARTED'");var grant=jdbc.queryForMap("SELECT * FROM agent_command_v2_execution_grant ORDER BY id DESC LIMIT 1");return AgentRuntimeV2ExecutionCodec.encodeRenew(new AgentRuntimeV2ExecutionCodec.Renew(UUID.randomUUID().toString(),origin().text("runtime_dispatch_id"),(String)started.get("receipt_id"),HexFormat.of().formatHex((byte[])started.get("receipt_wire_sha256")),(String)grant.get("execution_grant_id"),HexFormat.of().formatHex((byte[])grant.get("grant_wire_sha256"))));}
        AgentRuntimeV2EffectCodec.Reconcile renewalQuery(byte[] raw){var renewal=AgentRuntimeV2ExecutionCodec.decodeRenew(raw);return new AgentRuntimeV2EffectCodec.Reconcile(UUID.randomUUID().toString(),renewal.runtimeDispatchId(),AgentRuntimeV2EffectCodec.Subject.RENEWAL,renewal.renewalRequestId(),raw,null);}
        AgentRuntimeV2DispatchSource.Locked rows(){return new AgentRuntimeV2DispatchSource.Locked(jdbc.queryForMap("SELECT * FROM agent_command_delivery"),jdbc.queryForMap("SELECT * FROM agent_outbox_event"),jdbc.queryForMap("SELECT * FROM agent_consumer_inbox"));}
        AgentRuntimeV2HistoricalSettlementEvidenceValidator.Graph historicalGraph(){return file.tx.execute(status->{var rows=rows();var graph=executionEvidence.inspect(rows,origin(),false);return effectEvidence.inspect(origin(),rows,graph,false);});}
        AgentRuntimeV2EffectCodec.EffectRequest completeFile(){
            return completeFile(false);
        }
        AgentRuntimeV2EffectCodec.EffectRequest completeFileViaServerSelectedDescriptor(){return completeFile(true);}
        private AgentRuntimeV2EffectCodec.EffectRequest completeFile(boolean selectBranch){
            var description=selectBranch?request(AgentRuntimeV2EffectCodec.Kind.WORKSPACE_PREPARE,AgentRuntimeV2EffectCodec.Phase.DESCRIBE,new AgentRuntimeV2EffectCodec.WorkspacePrepare("task-a","item-a",AgentRuntimeV2EffectServiceImpl.workspaceKey(origin()),null),null):request(AgentRuntimeV2EffectCodec.Kind.FILE_DESCRIBE,AgentRuntimeV2EffectCodec.Phase.DESCRIBE,new AgentRuntimeV2EffectCodec.FileDescribe("task-a","item-a"),null);
            var described=reduce(description);if(described.close())throw new AssertionError("Actual complete file description rejected");
            var selected=(AgentRuntimeV2EffectCodec.DescribedResult)AgentRuntimeV2EffectCodec.decodeReceipt(described.responses().getFirst()).result();if(!"WORKSPACE_FILE".equals(selected.branch()))throw new AssertionError("Actual persisted file association was not selected");
            String hash=AgentRuntimeV2RegistrationFingerprints.hex(AgentRuntimeV2RegistrationFingerprints.sha256(file.output));var staged=reduce(request(AgentRuntimeV2EffectCodec.Kind.FILE_STAGE,AgentRuntimeV2EffectCodec.Phase.STAGE,new AgentRuntimeV2EffectCodec.FileStage("exec-a","task-a","item-a","run-a","output_1",hash,file.output.length,0,file.output.length),file.output));if(staged.close())throw new AssertionError("Actual complete file stage rejected");
            var commit=request(AgentRuntimeV2EffectCodec.Kind.FILE_COMMIT,AgentRuntimeV2EffectCodec.Phase.COMMIT,file.commitRequest(),null);if(reduce(commit).close())throw new AssertionError("Actual complete file commit rejected");registration.clock.value=Math.max(registration.clock.value+1,System.currentTimeMillis()+1);return commit;
        }
        @Override public void close() throws Exception {try{file.close();}finally{schemaConnection.close();}}
    }
    /** Freshly compiled R1-R3 support prepares real crypto/origin/STARTED
     * graphs. R4 uses a new mapper, schema and the actual composite reducer;
     * no prior classpath/helper or copied fixture class is used. Resource
     * adapters below are UNUSED mocks for ordinary admission-only tests.
     * This is not file/skill publication or an engine containment fixture.
     */
    static final class EffectResource implements AutoCloseable {
        final AgentRuntimeV2ExecutionTransactionTest.Fixture original=new AgentRuntimeV2ExecutionTransactionTest.Fixture();
        final JdbcTemplate jdbc=original.f.jdbc;
        final AgentRuntimeV2EffectMapper effects=new AgentRuntimeV2EffectMapper(jdbc);
        final AgentRuntimeV2HistoricalSettlementEvidenceValidator history=new AgentRuntimeV2HistoricalSettlementEvidenceValidator(effects);
        final AgentRuntimeV2RecoveryAuthority recovery=new AgentRuntimeV2RecoveryAuthority(original.f.db,original.f.keys,original.f.clock);
        final PersonalWorkspaceExecutionService workspace=mock(PersonalWorkspaceExecutionService.class);
        final SkillInstallResultService skills=mock(SkillInstallResultService.class);
        final AgentRuntimeV2EffectServiceImpl reducer;
        final String runToken=UUID.randomUUID().toString();
        private final Connection schemaConnection;
        EffectResource() throws Exception {
            schemaConnection=Objects.requireNonNull(jdbc.getDataSource()).getConnection();
            try{
                String sql=AgentRuntimeV2EffectConfiguration.manualSql().replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
                new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).populate(schemaConnection);
                original.start();
                reducer=new AgentRuntimeV2EffectServiceImpl(original.store,effects,original.authority,recovery,original.evidence,history,workspace,skills,original.manager,original.f.clock);
            }catch(Exception failure){schemaConnection.close();throw failure;}
        }
        AgentRuntimeV2InternalOutboxRow origin(){return original.p.internal();}
        String workspaceKey(){return AgentRuntimeV2EffectServiceImpl.workspaceKey(origin());}
        AgentRuntimeV2EffectService.TrustedRoute route(){var socket=original.socket;return new AgentRuntimeV2EffectService.TrustedRoute(socket.sessionFence(),socket.socketOwnerInstanceId(),socket.socketOwnerEpoch());}
        AgentRuntimeV2EffectCodec.GrantReference grant(){var receipt=original.receipt();var grant=original.grant();return new AgentRuntimeV2EffectCodec.GrantReference(UUID.randomUUID().toString(),origin().text("runtime_dispatch_id"),(String)receipt.get("receipt_id"),HexFormat.of().formatHex((byte[])receipt.get("receipt_wire_sha256")),(String)grant.get("execution_grant_id"),HexFormat.of().formatHex((byte[])grant.get("grant_wire_sha256")));}
        AgentRuntimeV2EffectCodec.EffectRequest request(AgentRuntimeV2EffectCodec.Kind kind,AgentRuntimeV2EffectCodec.Phase phase,AgentRuntimeV2EffectCodec.Resource resource){var reference=grant();String id=AgentRuntimeV2EffectCodec.effectId(AgentRuntimeV2HistoricalSettlementEvidenceValidator.stableKey(origin()),HexFormat.of().formatHex(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256((byte[])jdbc.queryForMap("SELECT * FROM agent_command_delivery").get("command_payload"))),origin().text("runtime_dispatch_id"),(String)original.grant().get("original_start_lease_fact_id"),runToken,kind,phase,resource);return new AgentRuntimeV2EffectCodec.EffectRequest(reference,runToken,id,kind,phase,resource,null);}
        AgentRuntimeV2EffectService.Result reduce(AgentRuntimeV2EffectCodec.EffectRequest request){return reducer.effect(original.f.identity,original.socket,route(),AgentRuntimeV2EffectCodec.encode(request));}
        AgentRuntimeV2EffectService.Result reconcile(AgentRuntimeV2EffectCodec.Reconcile request){return reducer.reconcile(original.f.identity,original.socket,route(),AgentRuntimeV2EffectCodec.encode(request));}
        int count(String table){if(!Set.of(AgentRuntimeV2EffectStore.RUN,AgentRuntimeV2EffectStore.EFFECT,AgentRuntimeV2EffectStore.REQUEST,AgentRuntimeV2EffectStore.EVENT,AgentRuntimeV2EffectStore.BLOB).contains(table))throw new IllegalArgumentException("fixed R4 fixture table");return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
        @Override public void close() throws Exception {try{jdbc.execute("DROP ALL OBJECTS");}finally{schemaConnection.close();}}
    }
    public static final class SkillResource implements AutoCloseable {
        public static final HostingRentHttp.Actor ACTOR=new HostingRentHttp.Actor("r4-buyer","0","caller-C","owner-a");
        public static final String AGENT="agt_00000000000000000000000000000001";
        public final JdbcTemplate jdbc;
        public final TransactionTemplate tx;
        public final SkillInstallResultService results;
        public final ObjectProvider<SkillPackages> resultPackages;
        public final SkillPackages packageStore;
        public final EconomySkillMarketplaceMapper market;
        public final SkillAgentVersions legacyVersions;
        public final AgentCommandTransportDao legacyCommands;
        public final AgentRuntimeV2InternalOutboxRow origin;
        public final AgentSkillInstallPayload payload;
        public final AgentRuntimeV2EffectCodec.SkillIdentity identity;
        public final long heldAt;
        private final Connection schemaConnection;

        public SkillResource() throws Exception {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:r4_skill_"+UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000","sa","");
            jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
            schemaConnection=ds.getConnection();
            try {
                for(String resource:List.of("db/economy-v0-foundation.sql","db/economy-v0-skill-marketplace.sql","db/economy-v0-skill-application.sql")){
                    String sql=new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8)
                        .replaceAll("(?is)\\)\\s*ENGINE=InnoDB\\s+DEFAULT\\s+CHARSET=utf8mb4(?:\\s+COLLATE\\s*=\\s*[a-z0-9_]+)?(?:\\s+COMMENT='(?:''|[^'])*')?\\s*;", ");");
                    // Schema SessionLocal stays open for H2's constant-IN DDL
                    // comparator. Only MySQL table options are removed: CHECK,
                    // FK, column and index definitions remain the real schema.
                    // Business operations obtain other connections.
                    new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).populate(schemaConnection);
                }
                var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
                for(Class<?> mapper:List.of(EconomySkillMarketplaceMapper.class,EconomySkillApplicationMapper.class,EconomyLedgerMapper.class))config.addMapper(mapper);
                var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
                var session=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
                market=spy(session.getMapper(EconomySkillMarketplaceMapper.class));var app=session.getMapper(EconomySkillApplicationMapper.class);var ledger=session.getMapper(EconomyLedgerMapper.class);
                var preview=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope("0","caller-C"))));
                var posting=new EconomyPostingServiceImpl(ledger,manager,preview);
                legacyVersions=mock(SkillAgentVersions.class);when(legacyVersions.enabled()).thenReturn(true);when(legacyVersions.requireOwned(any(),eq(AGENT),any(),anyBoolean())).thenReturn(1L);
                var runtimes=mock(AgentRuntimeDao.class);var runtime=new AgentRuntimeEntity().setAgentId(AGENT).setOwnerJiacn("owner-a").setBindingId(7L).setTokenHash("setup-registration").setStatus("online");runtime.setTenantId("0");runtime.setClientId("caller-C");runtime.setRuntimeInstallationId("rti_"+"a".repeat(32));runtime.setRuntimeHostId("setup-host");runtime.setRuntimeInstanceId("setup-runtime");runtime.setRuntimeSessionGeneration(1L);when(runtimes.findByAgentIdForUpdate(AGENT)).thenReturn(runtime);when(runtimes.findInScope("0","caller-C",AGENT)).thenReturn(runtime);
                var gate=mock(AgentRabbitSafetyGate.class);when(gate.rabbitDispatchEnabled()).thenReturn(true);when(gate.allowsDispatch("0","caller-C")).thenReturn(true);
                packageStore=spy(new SkillPackages());
                var authentication=mock(cn.jia.agent.security.AgentRuntimeAuthenticationService.class);var proof=new cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof(new cn.jia.agent.security.AgentRuntimeAuthentication.Scope("0","caller-C","owner-a",AGENT,"setup-runtime"),runtime.getRuntimeInstallationId(),runtime.getRuntimeHostId(),runtime.getRuntimeSessionGeneration(),"a".repeat(64),1L,1L);when(authentication.currentRegisteredProof("0","caller-C",AGENT)).thenReturn(proof);when(authentication.withFence(eq(proof),eq(false),any())).thenAnswer(invocation->((java.util.function.Supplier<?>)invocation.getArgument(2)).get());
                var sessions=mock(AgentManagedSessionLookup.class);when(sessions.isReady(eq("0"),eq("caller-C"),eq(AGENT),any())).thenReturn(true);
                var setupCommand=new java.util.concurrent.atomic.AtomicReference<AgentCommandDraft>();var writer=mock(SkillInstallCommandWriter.class);when(writer.allows("0","caller-C")).thenReturn(true);when(writer.write(any())).thenAnswer(invocation->{var command=(AgentCommandDraft)invocation.getArgument(0);setupCommand.set(command);return new AgentCommandTransportWriteResult(1L,command.commandId(),"setup-message","setup-outbox",false);});
                var marketplace=new SkillMarketplaceService(market,app,ledger,posting,preview,legacyVersions,runtimes,provider(writer),provider(packageStore),provider(authentication),provider(sessions),manager);
                legacyCommands=mock(AgentCommandTransportDao.class);resultPackages=provider(packageStore);results=new SkillInstallResultService(market,app,legacyCommands,marketplace,legacyVersions,authentication,resultPackages,manager);
                var product=new PlatformSkillPackageCatalog().products().stream().filter(value->"spv_repo_test_1_0_0".equals(value.productVersionId())).findFirst().orElseThrow();
                market.insertProduct(new SkillProductEntity().setProductId(product.productId()).setSellerType("SYSTEM").setSellerId("SKILL_STORE").setName(product.name()).setDescription(product.description()).setStatus("DRAFT").setVersion(1L).setTenantId("0").setClientId("caller-C").setCreateTime(1L).setUpdateTime(1L));
                market.insertProductVersion(new SkillProductVersionEntity().setProductVersionId(product.productVersionId()).setProductId(product.productId()).setVersionSequence(1L).setSkillKey(product.skillKey()).setSkillVersion(product.skillVersion()).setPriceMicro(product.priceMicro()).setPackageSha256(product.packageSha256()).setPackageSize(product.packageSize()).setApprovedPermissionsManifest(product.approvedPermissionsManifest()).setApprovedPermissionsSha256(product.approvedPermissionsSha256()).setDeploymentRestriction(product.deploymentRestriction()).setReviewStatus("APPROVED").setTenantId("0").setClientId("caller-C").setCreateTime(1L));
                market.publishProductVersion("0","caller-C",product.productId(),product.productVersionId(),1L,2L);
                ledger.insertAccountIfAbsent(new EconomyAccountEntity().setAccountId("r4-wallet").setOwnerType("USER").setOwnerId(ACTOR.actorId()).setPurpose("AVAILABLE").setCurrency("SILVER").setBalanceMicro(100000000L).setAllowNegative(0).setStatus("ACTIVE").setVersion(0L).setTenantId("0").setClientId("caller-C").setCreateTime(1L).setUpdateTime(1L));
                var quote=marketplace.quote(ACTOR,UUID.randomUUID().toString(),Map.of("productVersionId",product.productVersionId(),"targetAgentId",AGENT,"expectedAgentVersion","1"),false);
                var purchased=marketplace.purchase(ACTOR,UUID.randomUUID().toString(),Map.of("quoteId",(String)quote.get("quoteId"),"productVersionId",product.productVersionId(),"targetAgentId",AGENT,"expectedAgentVersion","1","expectedPriceMicro",Long.toString(product.priceMicro()),"approvedPermissions",product.approvedPermissionsManifest()),false);
                String order=(String)purchased.get("orderId");var installations=jdbc.queryForList("SELECT installation_id FROM economy_skill_installation WHERE tenant_id='0' AND client_id='caller-C' AND order_id=?",order);if(installations.size()!=1)throw new IllegalStateException("one real installation required");String installation=(String)installations.getFirst().get("installation_id");var installed=app.installation("0","caller-C",installation);
                payload=(AgentSkillInstallPayload)Objects.requireNonNull(setupCommand.get()).payload();
                identity=new AgentRuntimeV2EffectCodec.SkillIdentity(installation,product.productVersionId(),payload.packageDigest(),product.skillKey(),product.skillVersion());
                heldAt=app.order("0","caller-C",order).getHeldAt();
                origin=resourceOrigin(Objects.requireNonNull(setupCommand.get()),installed.getMessageId());
                // Setup legitimately used V1 marketplace purchase admission;
                // V2 operations below must not consult these old fences.
                clearInvocations(legacyVersions,legacyCommands,runtimes,authentication,sessions,writer,resultPackages,packageStore);
            } catch(Exception failure){schemaConnection.close();throw failure;}
        }
        public String result(String effect,byte[] proof,SkillInstallResultService.V2Disposition disposition,Long installedAt,long now){return tx.execute(status->results.v2Result(origin,payload,identity,effect,proof,disposition,installedAt,now));}
        public SkillInstallResultService.V2PreparedPackage prepare(AgentRuntimeV2EffectCodec.SkillRead read){var plan=tx.execute(status->results.v2PlanPackage(origin,payload,read));return results.v2PreparePackage(plan);}
        public AgentRuntimeV2EffectCodec.ReadResult read(AgentRuntimeV2EffectCodec.SkillRead read){var prepared=prepare(read);return tx.execute(status->results.v2Package(origin,payload,read,prepared));}
        public int count(String table){if(!Set.of("economy_transaction","economy_entry","economy_skill_result_receipt").contains(table))throw new IllegalArgumentException("fixed fixture table");return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
        public long wallet(){return jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE account_id='r4-wallet'",Long.class);}
        public String status(String table){if(!Set.of("economy_skill_order","economy_skill_installation","economy_skill_entitlement").contains(table))throw new IllegalArgumentException("fixed fixture table");return jdbc.queryForObject("SELECT status FROM "+table+" WHERE tenant_id='0' AND client_id='caller-C'",String.class);}
        @Override public void close() throws Exception {try{jdbc.execute("DROP ALL OBJECTS");}finally{schemaConnection.close();}}
    }
    /** Fully validated production row shape, with codec-produced bytes and
     * origin digest. These test provenance IDs are not a registered ACL. */
    private static AgentRuntimeV2InternalOutboxRow resourceOrigin(AgentCommandDraft command,String message){
        String dispatch=UUID.randomUUID().toString(),binding=UUID.randomUUID().toString(),instance=UUID.randomUUID().toString(),fact=UUID.randomUUID().toString();byte[] snapshot=cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256("r4-explicit-resource-test-provenance".getBytes(StandardCharsets.UTF_8));
        var producer=new ProducerScope(TenantId.of("0"),DataDomainId.of("r4-domain"),"r4-test","producer-a",1L,"agent",1L);
        byte[] raw=AgentCommandCanonicalCodec.wireBytesV2(command,message,1L,1,producer),outer=AgentRuntimeV2DispatchCodec.encode(new AgentRuntimeV2DispatchCodec.Delivery(dispatch,raw));
        var origin=new ExecutionOriginSnapshot(dispatch,1L,1L,"0","r4-domain","r4-test","caller-C","receiver-R","owner-a",SkillResource.AGENT,"producer-a","local-api-a",1L,1L,1L,"agent",1L,binding,1L,instance,fact,snapshot);
        var row=new LinkedHashMap<String,Object>();AgentRuntimeV2InternalOutboxRow.COLUMNS.forEach(name->row.put(name,null));
        row.putAll(cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("internal_outbox_id",UUID.randomUUID().toString(),"contract_version",2,"runtime_dispatch_id",dispatch,"inbox_id",1L,"delivery_id",1L,"source_outbox_id",1L,"source_event_id","setup-outbox","source_message_id",message,"command_id",command.commandId(),"tenant_id","0","data_domain_id","r4-domain","execution_pool_id","r4-test","command_caller_client_id","caller-C","runtime_credential_client_id","receiver-R","owner_jiacn","owner-a","agent_id",SkillResource.AGENT,"producer_deployment_id","producer-a","claimant_deployment_id","local-api-a","claimant_worker_generation",1L,"receiver_registration_revision",1L,"admission_epoch",1L,"module_id","agent","migration_epoch",1L,"runtime_binding_id",binding,"dispatch_binding_generation",1L,"runtime_instance_id",instance,"dispatch_generation_fact_id",fact,"dispatch_generation_snapshot_sha256",snapshot,"execution_origin_snapshot_sha256",origin.sha256(),"session_fence",UUID.randomUUID().toString(),"socket_owner_instance_id",UUID.randomUUID().toString(),"socket_owner_epoch",1L,"prepared_socket_owner_lease_until",command.expiresAt(),"command_wire_bytes",raw,"command_wire_sha256",cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(raw),"dispatch_wire_bytes",outer,"dispatch_wire_sha256",cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(outer),"prepared_at",command.issuedAt(),"expires_at",command.expiresAt(),"status","PENDING","attempt_count",0,"next_attempt_at",command.issuedAt(),"version",0L,"update_time",command.issuedAt()));
        return new AgentRuntimeV2InternalOutboxRow(row);
    }
    private static <T>ObjectProvider<T> provider(T value){var provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(value);when(provider.getIfAvailable()).thenReturn(value);return provider;}
}
