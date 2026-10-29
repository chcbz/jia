package cn.jia.chat.archive.service;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.position.*;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.store.*;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import cn.jia.economy.entity.skill.SkillProductVersionEntity;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static cn.jia.agent.position.PositionSkillState.*;
import static cn.jia.chat.archive.service.ArchiveMaintenanceService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real production SQL and transactions; identity/catalog are explicit doubles.
 * The generic installed-skill source runs as an actual Node process. Not native HTTP/auth E2E. */
class ArchiveMaintenanceMySqlTest {
    JdbcTemplate admin,jdbc; String database,appointment; PositionSkillService positions;
    ArchiveMaintenanceService service; JdbcArchiveContentStore content; SpringArchiveTransactions tx;
    final Scope scope=new Scope("0","test-client","owner-a");
    final ObjectMapper json=JsonMapper.builderWithJackson2Defaults().build();
    final Proof proof=new Proof(new AgentRuntimeAuthentication.Scope("0","test-client","owner-a","agent1","instance1"),"runtime1","host1",1,"test-digest",1,1);
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
        var market=mock(EconomySkillMarketplaceMapper.class);
        when(market.selectApprovedProductVersion("0","test-client","v1")).thenReturn(new SkillProductVersionEntity().setReviewStatus("APPROVED").setSkillKey("archive-maintenance"));
        var manager=new DataSourceTransactionManager(ds);tx=new SpringArchiveTransactions(manager);content=new JdbcArchiveContentStore(jdbc);
        positions=new PositionSkillService(new PositionSkillStore(jdbc),identities,market,manager);
        service=new ArchiveMaintenanceService(positions,identities,new ArchiveMaintenanceStore(jdbc),content,tx);
        for(String permission:List.of("MANAGE_POSITIONS","ARCHIVE_WRITE","ARCHIVE_PUBLISH"))jdbc.update("INSERT INTO agent_position_authority VALUES(?,?,?,?,?)","0","test-client","owner-a",permission,permission.equals("MANAGE_POSITIONS")?"positions":"archive:public");
        positions.defineSkill(scope,"skill",new Skill("archive-maintenance",SkillType.POSITION,"v1"));
        positions.configure(scope,"position",new Position("editor","Editor",2,"archive:public",true,List.of("archive-maintenance"),0),0);
        appointment=positions.appoint(scope,"appoint","editor","agent1").resultId();
        positions.runtimeState(scope,"agent1",p->{var i=p.state().installations().values().iterator().next();p.receipt(i.id(),i.revision(),true,"a".repeat(64),"runtime1","host1",null,"sha256:"+"b".repeat(64),"c".repeat(64));return null;});
    }
    @AfterEach void cleanup(){if(admin!=null && database!=null && database.matches("aps_archive_[a-f0-9]{32}"))admin.execute("DROP DATABASE "+database);}
    Create request(String work,Mode mode,String source){return new Create(appointment,"agent1","archive-maintenance",work,"Fixture "+work,"Test Author","External test source","Self-authored test data",source,mode);}
    Map<String,Object> job(String key,String work,Mode mode,String source){return service.create(scope,key,request(work,mode,source));}
    Result parse(Map<String,Object> job)throws Exception {
        String script=System.getenv("CYF_ARCHIVE_SKILL_SCRIPT");assertNotNull(script,"External skill checkout must be explicit");
        var process=new ProcessBuilder("node",script).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        process.getOutputStream().write(json.writeValueAsBytes(service.source(proof,(String)job.get("jobId"))));process.getOutputStream().close();
        byte[] output=process.getInputStream().readAllBytes();assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        return json.readValue(output,Result.class);
    }
    @Test void draftNeverChangesShelfAndLostResponseReturnsOriginalJob()throws Exception {
        var request=request("draft-book",Mode.DRAFT_ONLY,"第一回 新章\n这是自编的测试正文。\n第二段内容。");
        var j=service.create(scope,"create",request);var result=parse(j);
        assertEquals("DRAFT",service.complete(proof,(String)j.get("jobId"),result).get("state"));
        assertNull(content.findWork("draft-book"));assertEquals(0,new ArchiveReaderServiceImpl(content).books().items().size());
        assertEquals(j.get("jobId"),service.create(scope,"create",request).get("jobId"));
        assertEquals(j.get("jobId"),service.recover(scope,"create").get("jobId"));
        assertFalse(service.read(scope,(String)j.get("jobId")).containsKey("sourceText"));
        assertThrows(PositionSkillException.class,()->service.create(scope,"create",request("draft-book",Mode.AUTO,"other")));
    }
    @Test void twoNewDataOnlyBooksPublishWithNoPrefaceAndDistinctStructure()throws Exception {
        for(var sample:Map.of("book-a","第一回 开场\n正文甲。\n第二回 下回\n正文乙。","book-b","# Start\nParagraph one.\n\n# End\nParagraph two.").entrySet()){
            var j=job(sample.getKey(),sample.getKey(),Mode.AUTO,sample.getValue());var result=parse(j);
            var done=service.complete(proof,(String)j.get("jobId"),result);assertEquals("PUBLISHED",done.get("state"));
            var reader=new ArchiveReaderServiceImpl(content);var catalog=reader.catalog(sample.getKey()).data();
            assertNull(catalog.activeEdition().preface());assertEquals(2,catalog.activeEdition().chapters().size());
            var first=catalog.activeEdition().chapters().getFirst();assertFalse(reader.chapter((String)j.get("editionId"),first.blockId()).data().paragraphs().isEmpty());
            assertEquals(done,service.complete(proof,(String)j.get("jobId"),result));
        }
        assertEquals(2,new ArchiveReaderServiceImpl(content).books().items().size());
    }
    @Test void revisionKeepsOldEditionReadableAndContentsImmutable()throws Exception {
        var first=job("first","revision-book",Mode.AUTO,"第一回\n旧版本正文。");var result=parse(first);service.complete(proof,(String)first.get("jobId"),result);
        var second=job("second","revision-book",Mode.AUTO,"第一回\n新版本正文。");service.complete(proof,(String)second.get("jobId"),parse(second));
        var reader=new ArchiveReaderServiceImpl(content);String old=(String)first.get("editionId"),active=(String)second.get("editionId");
        assertEquals(active,reader.catalog("revision-book").data().activeEdition().editionId());
        var history=reader.catalogEdition("revision-book",old).data();assertEquals(old,history.activeEdition().editionId());
        var chapter=reader.chapter(old,history.activeEdition().chapters().getFirst().blockId()).data();assertEquals("旧版本正文。",chapter.paragraphs().getFirst().text());
        var modified=result.manifestJson().replace("旧版本正文。","假版本正文。");assertThrows(RuntimeException.class,()->service.complete(proof,(String)first.get("jobId"),new Result(modified,PositionSkillService.hash(modified))));
    }
    @Test void historicalBookmarkAndNoteStayWithOriginalOwnerAndEditionAfterRevision()throws Exception {
        var first=job("personal-first","personal-book",Mode.AUTO,"第一回\n原版本私人定位正文。");
        service.complete(proof,(String)first.get("jobId"),parse(first));String old=(String)first.get("editionId");
        var reader=new ArchiveReaderServiceImpl(content);var catalog=reader.catalog("personal-book").data();
        var block=catalog.activeEdition().chapters().getFirst();var paragraph=reader.chapter(old,block.blockId()).data().paragraphs().getFirst();
        var personal=new ArchivePersonalDataServiceImpl(new JdbcArchivePersonalDataStore(jdbc),tx);
        var owner=new cn.jia.chat.archive.model.ArchiveOwnerScope("0","test-client","owner-a");
        var other=new cn.jia.chat.archive.model.ArchiveOwnerScope("0","test-client","owner-b");
        String bookmark=UUID.randomUUID().toString(),note=UUID.randomUUID().toString(),path="/archive/v1/me/bookmarks/"+bookmark;
        var location=Map.of("editionManifestSha256",catalog.activeEdition().manifestSha256(),"blockType","CHAPTER","blockId",block.blockId(),"paragraphId",paragraph.paragraphId(),"byteOffset",0,"paragraphSha256",paragraph.sha256());
        assertEquals(200,personal.putBookmark(owner,bookmark,path,"bookmark-create",json.writeValueAsBytes(Map.of("expectedVersion","0","editionId",old,"location",location))).status());
        var noteBody=new LinkedHashMap<String,Object>();noteBody.put("expectedVersion","0");noteBody.put("editionId",old);noteBody.put("text","PRIVATE TEST NOTE");noteBody.put("anchor",null);
        assertEquals(200,personal.putNote(owner,note,"/archive/v1/me/notes/"+note,"note-create",json.writeValueAsBytes(noteBody)).status());
        var second=job("personal-second","personal-book",Mode.AUTO,"第一回\n新版不同正文。");service.complete(proof,(String)second.get("jobId"),parse(second));
        assertEquals(1,personal.bookmarks(owner,old,null,20).items().size());assertEquals(1,personal.notes(owner,old,null,null,20).items().size());
        assertTrue(personal.bookmarks(owner,(String)second.get("editionId"),null,20).items().isEmpty());assertTrue(personal.notes(owner,(String)second.get("editionId"),null,null,20).items().isEmpty());
        assertTrue(personal.bookmarks(other,old,null,20).items().isEmpty());assertTrue(personal.notes(other,old,null,null,20).items().isEmpty());
        assertEquals(200,personal.putBookmark(owner,bookmark,path,"bookmark-historical-update",json.writeValueAsBytes(Map.of("expectedVersion","1","editionId",old,"location",location))).status());
        jdbc.update("UPDATE archive_work SET active_edition_id=NULL WHERE work_id='personal-book'");
        assertThrows(ArchivePersonalDataException.class,()->personal.putBookmark(owner,bookmark,path,"bookmark-withdrawn",json.writeValueAsBytes(Map.of("expectedVersion","2","editionId",old,"location",location))));
    }
    @Test void concurrentNewBookJobsUseCasAndRollbackLosingImport()throws Exception {
        var one=job("one","race-book",Mode.AUTO,"第一回\n第一份正文。");var two=job("two","race-book",Mode.AUTO,"第一回\n第二份正文。");
        var r1=parse(one);var r2=parse(two);service.complete(proof,(String)one.get("jobId"),r1);
        var failed=service.complete(proof,(String)two.get("jobId"),r2);
        assertEquals("FAILED",failed.get("state"));assertEquals("ARCHIVE_EDITION_CONFLICT",failed.get("errorCode"));
        assertEquals(failed,service.complete(proof,(String)two.get("jobId"),r2));
        assertTrue(service.commands(proof).isEmpty());assertNull(content.findEdition((String)two.get("editionId")));
        var retry=service.retry(scope,"retry-cas",(String)two.get("jobId"));
        assertEquals("PUBLISHED",service.complete(proof,(String)retry.get("jobId"),parse(retry)).get("state"));
    }
    @Test void resignationFencesResultButCommittedReceiptRemainsRecoverable()throws Exception {
        var published=job("published","before-revoke",Mode.AUTO,"正文。");service.complete(proof,(String)published.get("jobId"),parse(published));
        var pending=job("pending","after-revoke",Mode.AUTO,"正文。");var r=parse(pending);
        positions.resign(scope,"resign",appointment);
        assertThrows(PositionSkillException.class,()->service.complete(proof,(String)pending.get("jobId"),r));assertNull(content.findWork("after-revoke"));
        assertEquals("PUBLISHED",service.outcome(proof,(String)published.get("jobId")).get("state"));assertTrue(service.commands(proof).isEmpty());
    }
    @Test void historicalD2MaintenanceTableIsNeverAdoptedOrModified()throws Exception {
        jdbc.execute("CREATE TABLE archive_maintenance_job(legacy_marker VARCHAR(100) PRIMARY KEY)");
        jdbc.update("INSERT INTO archive_maintenance_job VALUES('preserve-history')");
        var created=job("new-model","isolated-new-model",Mode.DRAFT_ONLY,"新正文。");
        service.complete(proof,(String)created.get("jobId"),parse(created));
        assertEquals("preserve-history",jdbc.queryForObject("SELECT legacy_marker FROM archive_maintenance_job",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM archive_skill_maintenance_job",Integer.class));
    }
    @Test void concurrentSameKeyCreationReturnsOneJobEvenAfterRepeatableReadSnapshot()throws Exception {
        var start=new CountDownLatch(1);var request=request("same-key-book",Mode.DRAFT_ONLY,"正文。");
        try(var pool=Executors.newFixedThreadPool(4)){
            var calls=new ArrayList<Future<Map<String,Object>>>();
            for(int n=0;n<4;n++)calls.add(pool.submit(()->{start.await();return service.create(scope,"same-key",request);}));
            start.countDown();var ids=new HashSet<Object>();for(var f:calls)ids.add(f.get(20,TimeUnit.SECONDS).get("jobId"));
            assertEquals(1,ids.size());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM archive_skill_maintenance_job",Integer.class));
        }
    }
    @Test void explicitDraftPublicationRequiresLivePublishAuthorityAndPreservesEditionAndRecovery()throws Exception {
        var j=job("draft-create","manual-book",Mode.DRAFT_ONLY,"第一回\n待审核正文。");String id=(String)j.get("jobId");
        service.complete(proof,id,parse(j));jdbc.update("DELETE FROM agent_position_authority WHERE permission_key='ARCHIVE_PUBLISH'");
        assertThrows(PositionSkillException.class,()->service.publishDraft(scope,"publish",id));assertNull(content.findWork("manual-book"));
        jdbc.update("INSERT INTO agent_position_authority VALUES('0','test-client','owner-a','ARCHIVE_PUBLISH','archive:public')");
        var published=service.publishDraft(scope,"publish",id);assertEquals("PUBLISHED",published.get("state"));assertEquals(j.get("editionId"),published.get("editionId"));
        assertEquals(published,service.publishDraft(scope,"publish",id));assertEquals(published,service.recover(scope,"publish"));
        assertThrows(PositionSkillException.class,()->job("publish","another-book",Mode.AUTO,"正文。"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM archive_skill_maintenance_action",Integer.class));
        positions.resign(scope,"resign",appointment);assertEquals(published,service.recover(scope,"publish"));
    }
    @Test void manualDraftPublicationUsesTheSameActiveEditionCasAsAuto()throws Exception {
        var draft=job("draft","manual-race",Mode.DRAFT_ONLY,"待审核。");service.complete(proof,(String)draft.get("jobId"),parse(draft));
        var auto=job("auto","manual-race",Mode.AUTO,"先发布。");service.complete(proof,(String)auto.get("jobId"),parse(auto));
        assertThrows(PositionSkillException.class,()->service.publishDraft(scope,"publish",(String)draft.get("jobId")));
        assertEquals("DRAFT",service.read(scope,(String)draft.get("jobId")).get("state"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM archive_skill_maintenance_action",Integer.class));
    }
    @Test void parserFailureIsDurableAndRetryCreatesOneFreshJobFromPersistedSource()throws Exception {
        var original=job("failed","failed-book",Mode.AUTO,"第一回\n测试正文。");String id=(String)original.get("jobId");
        var result=new Result(null,null,"ARCHIVE_SCRIPT_FAILED");var failed=service.complete(proof,id,result);
        assertEquals("FAILED",failed.get("state"));assertEquals("ARCHIVE_SCRIPT_FAILED",failed.get("errorCode"));
        assertEquals(failed,service.complete(proof,id,result));assertTrue(service.commands(proof).isEmpty());assertNull(content.findWork("failed-book"));
        assertThrows(PositionSkillException.class,()->service.complete(proof,id,new Result(null,null,"SECRET_ERROR")));
        var retry=service.retry(scope,"retry-key",id);assertNotEquals(id,retry.get("jobId"));
        assertEquals(retry.get("jobId"),service.retry(scope,"retry-key",id).get("jobId"));
        assertEquals(original.get("sourceSha256"),retry.get("sourceSha256"));
        assertEquals("PUBLISHED",service.complete(proof,(String)retry.get("jobId"),parse(retry)).get("state"));
        assertEquals("FAILED",service.read(scope,id).get("state"));
        positions.resign(scope,"resign",appointment);assertThrows(PositionSkillException.class,()->service.retry(scope,"after-revoke",id));
    }
    @Test void forgedAgentRuntimeAndCrossOwnerCannotReadOrCommit()throws Exception {
        var j=job("create","secure-book",Mode.AUTO,"正文。");var result=parse(j);String id=(String)j.get("jobId");
        var other=new Proof(new AgentRuntimeAuthentication.Scope("0","test-client","owner-a","forged","instance1"),"runtime1","host1",1,"test",1,1);
        assertThrows(PositionSkillException.class,()->service.source(other,id));assertThrows(PositionSkillException.class,()->service.outcome(other,id));
        var host=new Proof(proof.scope(),"runtime1","different-host",1,"test",1,1);assertThrows(PositionSkillException.class,()->service.complete(host,id,result));
        assertThrows(PositionSkillException.class,()->service.read(new Scope("0","test-client","other-owner"),id));
        jdbc.update("DELETE FROM agent_position_authority WHERE permission_key='ARCHIVE_PUBLISH'");
        assertThrows(PositionSkillException.class,()->service.complete(proof,id,result));assertNull(content.findWork("secure-book"));
    }
}
