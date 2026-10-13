package cn.jia.chat.service;
import cn.jia.agent.service.*;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ChatSkillSourceAccessTest {
    final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
    final ChatConversationArchiveStore archive=mock(ChatConversationArchiveStore.class);
    final PersonalWorkspaceExecutionService files=mock(PersonalWorkspaceExecutionService.class);
    final SkillExecutionService skills=mock(SkillExecutionService.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
    final PersonalWorkspaceExecutionService.OwnerScope owner=new PersonalWorkspaceExecutionService.OwnerScope("0","client","owner");
    final ChatSkillSourceAccessService source=new ChatSkillSourceAccessService(conversations,scopes,archive,files,skills,jdbc,storage);
    @BeforeEach void init() {
        var conversation=new ChatConversationEntity().setId(1L).setJiacn("owner").setTaskId("task").setLifecycleGeneration(2L)
                .setConversationType("juyiting").setConversationScopeType("bounty").setConversationScopeKey("task:task").setTargetAgentIds("[\"agent\"]");
        conversation.setTenantId("0");conversation.setClientId("client");when(conversations.findScopedById("owner","client","1")).thenReturn(conversation);
        when(scopes.parsePersistedTargetAgentIds(anyString())).thenReturn(List.of("agent"));
    }
    Map<String,Object> workspace(byte[] bytes) {return Map.of("sourceKind","TASK_LINKED_WORKSPACE_VERSION","sourceIdentity",Map.of("kind","TASK_LINKED_WORKSPACE_VERSION","fileId","file","version","3","purpose","INPUT"),
            "contentMimeType","image/png","sha256",ChatSkillSourceAccessService.sha(bytes),"byteLength",Integer.toString(bytes.length));}
    Map<String,Object> row(byte[] bytes) {return Map.of("storage_uri","private-uri","content_hash",ChatSkillSourceAccessService.sha(bytes),"byte_length",bytes.length,"content_mime_type","image/png");}
    @Test void uploadedWorkspaceImageHasNoInventedProducerAndIsExactCurrentLink() {
        byte[] bytes={1,2,3};var input=workspace(bytes);when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row(bytes)));
        when(storage.read(any(),eq("private-uri"),anyString(),eq(3L),eq("image/png"))).thenReturn(new PersonalWorkspaceStorage.StoredContent(bytes,ChatSkillSourceAccessService.sha(bytes),3,"image/png"));
        assertArrayEquals(bytes,source.read(owner,"task","1",2,input).bytes());
        assertEquals(Set.of("kind","fileId","version","purpose"),((Map<?,?>)input.get("sourceIdentity")).keySet());verifyNoInteractions(archive,files,skills);
    }
    @Test void sourceRevokedDuringStorageReadIsRejectedAfterIo() {
        byte[] bytes={1,2,3};when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row(bytes)),List.of(row(bytes)),List.of());
        when(storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenReturn(new PersonalWorkspaceStorage.StoredContent(bytes,ChatSkillSourceAccessService.sha(bytes),3,"image/png"));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,assertThrows(ChatDeliberationException.class,()->source.read(owner,"task","1",2,workspace(bytes))).reason());
        verify(storage).read(any(),anyString(),anyString(),anyLong(),anyString());
    }
    @Test void wrongGenerationOrTaskNeverReadsPrivateBytes() {
        assertThrows(ChatDeliberationException.class,()->source.read(owner,"task","1",3,workspace(new byte[]{1})));
        assertThrows(ChatDeliberationException.class,()->source.read(owner,"foreign","1",2,workspace(new byte[]{1})));
        verifyNoInteractions(storage,files,archive,jdbc,skills);
    }
    @Test void assetUsesExactAuthorizedLineageRatherThanModelProducer() {
        byte[] bytes={1,2};var current=new ChatConversationArchiveStore.Source("asset",3,"1",2,"parent",1,"step","task","exec","run","out","image/png",ChatSkillSourceAccessService.sha(bytes),2);
        when(archive.findAuthorizedSource(any(),eq("1"),eq("asset"),eq(3L),eq("agent"))).thenReturn(current);
        var identity=new LinkedHashMap<>(ChatSkillSourceAccessService.assetIdentity(current));identity.put("producerExecutionId","forged");
        var input=Map.<String,Object>of("sourceKind","CURRENT_CONVERSATION_ASSET","sourceIdentity",identity,"contentMimeType","image/png","sha256",current.sha256(),"byteLength","2");
        assertThrows(ChatDeliberationException.class,()->source.requireAuthorized(owner,"task","1",2,input));verifyNoInteractions(files);
    }
    @Test void genericResultDigestAndCommittedChainAreRequiredAndDataIsNotAFile() {
        var preimage=new LinkedHashMap<String,Object>();preimage.put("schemaVersion",1);preimage.put("data",Map.of("x",0.1,"answer","真实"));preimage.put("artifacts",List.of());preimage.put("inputReceipts",List.of());
        String digest=ChatSkillSnapshotJson.sha(preimage);var manifest=new LinkedHashMap<>(preimage);manifest.put("resultDigest",digest);manifest.put("manifestId","manifest");
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("execution_id","exec")));
        when(skills.result(owner,"exec")).thenReturn(new SkillExecutionService.ResultView("exec","RESULT_COMMITTED","COMPLETE",null,"d".repeat(64),manifest));
        byte[] bytes=ChatSkillSnapshotJson.write(preimage.get("data")).getBytes(StandardCharsets.UTF_8);
        var input=Map.<String,Object>of("sourceKind","EXECUTION_RESULT","sourceIdentity",Map.of("kind","EXECUTION_RESULT","executionId","exec","resultDigest",digest),"contentMimeType","application/json","sha256",ChatSkillSourceAccessService.sha(bytes),"byteLength",Integer.toString(bytes.length));
        assertArrayEquals(bytes,source.read(owner,"task","1",2,input).bytes());verifyNoInteractions(files,storage,archive);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertThrows(ChatDeliberationException.class,()->source.read(owner,"task","1",2,input));
    }
}
