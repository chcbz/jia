package cn.jia.chat.archive.service;

import cn.jia.agent.entity.*;
import cn.jia.agent.position.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.chat.service.*;
import cn.jia.chat.archive.content.*;
import cn.jia.chat.archive.store.*;
import cn.jia.agent.mapper.skill.SkillCatalogMapper;
import cn.jia.agent.entity.skill.SkillProductVersionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.position.PositionSkillState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real archive/position SQL and transactions. File ACL and native admission also have boundary tests. */
class ArchiveConversationMySqlTest {
    JdbcTemplate admin,jdbc;String database,appointment;PositionSkillService positions;
    JdbcArchiveContentStore content;SpringArchiveTransactions tx;ArchiveMaintenanceStore store;
    ArchiveConversationTools tools;InstalledSkillDescriptorLookup descriptors;PersonalWorkspaceService files;
    ConversationToolProvider.Boundary boundary;String sourceSha;
    final Scope scope=new Scope("0","test-client","owner-a");
    final ConversationToolProvider.Context context=new ConversationToolProvider.Context(
        new PrivateConversationToolAccess.Subject("0","test-client","owner-a","agent1"),"runtime1","host1");
    @BeforeEach void setup()throws Exception {
        String base=System.getenv("CYF_POSITION_TEST_JDBC");
        assertNotNull(base,"Explicit owned MySQL required");
        assertTrue(base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql(?:\\?.*)?"));
        String user=System.getenv("CYF_POSITION_TEST_USER"),pass=System.getenv("CYF_POSITION_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,user,pass));database="aps_archive_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+database);
        var ds=new DriverManagerDataSource(base.replace("/mysql","/"+database),user,pass);jdbc=new JdbcTemplate(ds);
        for(String resource:List.of("db/agent-position-skills.sql","db/archive-schema.sql","db/archive-reader-data-schema.sql","db/archive-maintenance-jobs.sql")){
            String ddl=new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
            for(String sql:ddl.replaceAll("(?m)^--.*$", "").split(";"))if(!sql.isBlank())jdbc.execute(sql);
        }
        var identities=mock(AgentIdentityService.class);
        var identity=new AgentIdentityRegistryEntity().setCanonicalAgentId("agent1").setOwnerJiacn("owner-a").setBindingId(1L);
        when(identities.requireRegistrationIdentityInScope("0","test-client","owner-a","agent1")).thenReturn(identity);
        when(identities.lockActiveCanonicalAgentIdsInScope(anyString(),anyString(),anyString(),anyList())).thenAnswer(i->i.getArgument(3));
        var market=mock(SkillCatalogMapper.class);
        when(market.selectApprovedProductVersion("0","test-client","v1")).thenReturn(new SkillProductVersionEntity().setReviewStatus("APPROVED").setSkillKey("archive-maintenance"));
        var manager=new DataSourceTransactionManager(ds);tx=new SpringArchiveTransactions(manager);content=new JdbcArchiveContentStore(jdbc);
        positions=new PositionSkillService(new PositionSkillStore(jdbc),identities,market,manager);

        for(String permission:List.of("MANAGE_POSITIONS","ARCHIVE_WRITE","ARCHIVE_PUBLISH"))jdbc.update("INSERT INTO agent_position_authority VALUES(?,?,?,?,?)","0","test-client","owner-a",permission,permission.equals("MANAGE_POSITIONS")?"positions":"archive:public");
        positions.defineSkill(scope,"skill",new Skill("archive-maintenance",SkillType.POSITION,"v1"));
        positions.configure(scope,"position",new Position("editor","Editor",2,"archive:public",true,List.of("archive-maintenance"),0),0);
        appointment=positions.appoint(scope,"appoint","editor","agent1").resultId();
        positions.runtimeState(scope,"agent1",p->{var i=p.state().installations().values().iterator().next();p.receipt(i.id(),i.revision(),true,"a".repeat(64),"runtime1","host1",null,"sha256:"+"b".repeat(64),null);return null;});
        nativeTools();
    }
    void nativeTools() {
        descriptors=mock(InstalledSkillDescriptorLookup.class);files=mock(PersonalWorkspaceService.class);
        var installation=positions.read(scope).state().installations().values().iterator().next();
        when(descriptors.readCurrent(any())).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(
            new InstalledSkillDescriptorLookup.Descriptor("archive-maintenance","2.0.0",installation.id(),"b".repeat(64),null,null,"runtime1","host1",null))));
        store=new ArchiveMaintenanceStore(jdbc);tools=new ArchiveConversationTools(new PositionArchiveAccessService(positions),descriptors,files,store,content,tx);
        boundary=new ConversationToolProvider.Boundary(){public <T>T execute(java.util.function.Supplier<T> action){return tx.required(action);}};
        source("第一回 开端\n甲。\n第二回 结尾\n乙。\n");
    }
    @AfterEach void cleanup(){if(admin!=null && database!=null && database.matches("aps_archive_[a-f0-9]{32}"))admin.execute("DROP DATABASE "+database);}
    void source(String text) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);sourceSha=ArchiveEtags.sha256(bytes);
        var s=new PersonalWorkspaceService.Scope("0","test-client","owner-a");
        var f=new PersonalWorkspaceViews.FileView("file1","UPLOAD","UPLOAD","底本","TEXT","ACTIVE",1,1,1,null);
        var v=new PersonalWorkspaceViews.VersionView("file1",1,"source.txt","text/plain",bytes.length,sourceSha,1,"READY");
        when(files.get(s,"file1")).thenReturn(new PersonalWorkspaceViews.FileDetailView(f,v,List.of(v),List.of(),List.of()));
        when(files.versions(s,"file1")).thenReturn(List.of(v));
        when(files.readContent(s,"file1",1)).thenReturn(new PersonalWorkspaceService.Content("source.txt","text/plain",bytes));
    }
    Map<String,Object> args(String key,String work) {return new LinkedHashMap<>(Map.of("operationKey",key,"fileId","file1","fileVersion","1","sourceSha256",sourceSha,"workId",work,"title","测试典籍","author","测试作者","sourceName","自写测试底本","sourceRights","测试自有文本"));}
    @SuppressWarnings("unchecked") Map<String,Object> call(String tool,Map<String,Object> args){return (Map<String,Object>)tools.call(context,boundary,tool,args);}
    Map<String,Object> draft(String key,String work){return call("archive_prepare",args(key,work));}
    Map<String,Object> publish(String key,Object id){return call("archive_publish",Map.of("operationKey",key,"jobId",id));}
    @Test void nativeInstructionCatalogAndFullDraftPublishReadback() {
        assertEquals(5,tools.catalog(context,boundary).tools().size());
        var draft=draft("prepare","novel");assertEquals("DRAFT",draft.get("state"));assertEquals(false,draft.get("active"));assertNull(content.findWork("novel"));
        assertEquals("2",draft.get("chapterCount"));assertEquals("第二回 结尾",draft.get("lastChapter"));
        var published=publish("publish",draft.get("jobId"));assertEquals("PUBLISHED",published.get("state"));assertEquals(true,published.get("active"));
        var stored=content.listAllParagraphs((String)draft.get("editionId"));assertEquals(List.of("甲。","乙。"),stored.stream().map(x->x.text()).toList());
        assertEquals(published,call("archive_read",Map.of("jobId",draft.get("jobId"))));
        verify(files).readContent(new PersonalWorkspaceService.Scope("0","test-client","owner-a"),"file1",1);
    }
    @Test void sameKeyRecoversExactResultDifferentInputConflictsWithoutOverwrite() {
        var draft=draft("prepare","novel");assertEquals(draft,draft("prepare","novel"));
        var changed=args("prepare","novel");changed.put("sourceRights","other");
        assertEquals(409,assertThrows(PositionSkillException.class,()->call("archive_prepare",changed)).status());
        var result=publish("publish",draft.get("jobId"));assertEquals(result,publish("publish",draft.get("jobId")));
        assertEquals(result,call("archive_recover",Map.of("operationKey","publish")));
        assertEquals(result,call("archive_recover",Map.of("operationKey","prepare")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM archive_skill_maintenance_job",Integer.class));
    }
    @Test void losingEditionCasLeavesDraftAndPreservesWinner() {
        var first=draft("first","novel");var second=draft("second","novel");publish("publish-first",first.get("jobId"));
        assertEquals(409,assertThrows(PositionSkillException.class,()->publish("publish-second",second.get("jobId"))).status());
        assertEquals(first.get("editionId"),content.findWork("novel").activeEditionId());
        assertEquals("DRAFT",call("archive_read",Map.of("jobId",second.get("jobId"))).get("state"));
    }
    @Test void resignationAfterSourceReadPreventsDraftCommit() {
        var actual=files.readContent(new PersonalWorkspaceService.Scope("0","test-client","owner-a"),"file1",1);
        when(files.readContent(any(),eq("file1"),eq(1))).thenAnswer(i->{positions.resign(scope,"resign",appointment);return actual;});
        assertEquals(403,assertThrows(PositionSkillException.class,()->draft("prepare","novel")).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM archive_skill_maintenance_job",Integer.class));
    }
    @Test void revokedPublishPermissionCannotActivateDraft() {
        var draft=draft("prepare","novel");jdbc.update("DELETE FROM agent_position_authority WHERE permission_key='ARCHIVE_PUBLISH'");
        assertEquals(403,assertThrows(PositionSkillException.class,()->publish("publish",draft.get("jobId"))).status());assertNull(content.findWork("novel"));
    }
    @Test void wrongNativeHostAndUninstalledDescriptorAreNotTools() {
        var other=new ConversationToolProvider.Context(context.subject(),"runtime1","other-host");
        assertTrue(tools.catalog(other,boundary).tools().isEmpty());
        when(descriptors.readCurrent(any())).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of()));
        assertTrue(tools.catalog(context,boundary).tools().isEmpty());
        assertThrows(PositionSkillException.class,()->draft("prepare","novel"));
    }
    @Test void badDigestEmptyChapterAndUnknownResponseDoNotCommit() {
        var invalid=args("prepare","novel");invalid.put("sourceSha256","a".repeat(64));assertThrows(PositionSkillException.class,()->call("archive_prepare",invalid));
        source("第一回 空章\n第二回 有文\n段落\n");assertThrows(PositionSkillException.class,()->draft("prepare","novel"));
        assertEquals(404,assertThrows(PositionSkillException.class,()->call("archive_recover",Map.of("operationKey","prepare"))).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM archive_skill_maintenance_job",Integer.class));
    }
    @Test void canceledTurnDuringStorageReadCannotCommit() {
        var original=boundary;var count=new java.util.concurrent.atomic.AtomicInteger();
        boundary=new ConversationToolProvider.Boundary(){public <T>T execute(java.util.function.Supplier<T> action){
            if(count.incrementAndGet()>1)throw new PrivateConversationToolAccess.Denied();return original.execute(action);}};
        assertThrows(PrivateConversationToolAccess.Denied.class,()->draft("prepare","novel"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM archive_skill_maintenance_job",Integer.class));
    }
    @Test void ownerScopeIsNeverTakenFromToolArguments() {
        var args=args("prepare","novel");args.put("ownerJiacn","other");assertThrows(PositionSkillException.class,()->call("archive_prepare",args));verifyNoInteractions(files);
    }
    @Test void legacyEndpointsHaveNoExecutionOrReplayMappings() {
        for(var type:List.of(cn.jia.chat.archive.controller.ArchiveMaintenanceController.class,
                cn.jia.chat.archive.controller.ArchiveMaintenanceRuntimeController.class))
            for(var method:type.getDeclaredMethods())assertNull(method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class));
        assertFalse(Arrays.stream(ArchiveMaintenanceService.class.getMethods()).anyMatch(m->Set.of("create","retry","complete","source","commands","publishDraft").contains(m.getName())));
    }
    @Test void historicalCommittedFactsRemainReadableButCannotBeRepublished() throws Exception {
        var d=draft("prepare","novel");var original=store.find(new cn.jia.chat.archive.model.ArchiveOwnerScope("0","test-client","owner-a"),(String)d.get("jobId"),false);
        var mapper=new ObjectMapper();var spec=mapper.readValue(original.specification(),ArchiveMaintenanceService.Specification.class);
        var legacy=new ArchiveMaintenanceService.Specification(spec.request(),spec.editionId(),spec.sourceSha256(),spec.grant(),spec.expectedActiveEdition(),spec.installationId(),spec.packageDigest(),"c".repeat(64));
        jdbc.update("UPDATE archive_skill_maintenance_job SET specification=? WHERE job_id=?",mapper.writeValueAsString(legacy),d.get("jobId"));
        assertEquals("DRAFT",new ArchiveMaintenanceService(store).read(scope,(String)d.get("jobId")).get("state"));
        assertEquals(409,assertThrows(PositionSkillException.class,()->publish("publish",d.get("jobId"))).status());assertNull(content.findWork("novel"));
    }
}
