package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskArtifactContentDTO;
import cn.jia.agent.service.AgentTaskArtifactContentService;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatFormalDeliveryRetentionServiceTest {
    final AgentTaskArtifactContentService contents=mock(AgentTaskArtifactContentService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<ChatActionFinalService> finals=mock(ObjectProvider.class);
    final ChatTypedDeliberationStore typed=mock(ChatTypedDeliberationStore.class);
    final ChatFormalDeliveryRetentionService service=new ChatFormalDeliveryRetentionService(mock(JdbcTemplate.class),mock(ChatDeliberationDao.class),
            typed,finals,mock(AgentTaskFormalDeliveryReadService.class),contents);
    static final byte[] BYTES="固定正式成果".getBytes(StandardCharsets.UTF_8);
    static String hash(){return ChatSkillSourceAccessService.sha(BYTES);}
    static ChatDeliveryRevisionService.BoundRevision bound() {return bound(List.of("target"));}
    static ChatDeliveryRevisionService.BoundRevision bound(List<String> targets) {
        var scope=new ChatTypedDeliberationStore.Scope("0","owner","client","7",1);
        var items=List.of(new ChatDeliveryRevisionContract.Item("target","1",hash(),"RESULT","RESULT"),
                new ChatDeliveryRevisionContract.Item("keep-a","2",hash(),"RESULT","RESULT"),
                new ChatDeliveryRevisionContract.Item("keep-b","3",hash(),"RESULT","RESULT"),
                new ChatDeliveryRevisionContract.Item("manifest","1",hash(),"manifest","MANIFEST"));
        var basis=new ChatDeliveryRevisionContract.Basis("task_fixture","7","delivery","0",
                ChatDeliveryRevisionContract.batch("task_fixture","delivery","0",items),"1","100","discussion_parent_fixture",items);
        var fixture=new ChatActionOutcomeV5Test().fixture();
        @SuppressWarnings("unchecked") var binding=(Map<String,Object>)fixture.get("binding");
        @SuppressWarnings("unchecked") var outcome=new LinkedHashMap<String,Object>((Map<String,Object>)fixture.get("outcome"));
        outcome.put("revision",Map.of("instruction","修改target","targetArtifactIds",targets));
        var valid=ChatActionFinalValidator.validateJson(binding,fixture.get("facts"),null,(String)outcome.get("text"),5,
                ChatDeliveryReviewContract.json(outcome),null,basis);
        var row=new ChatTypedDeliberationStore.Outcome("outcome",scope,"discussion_parent_fixture",1,"turn","task_fixture",1,101,
                valid.finalDigest(),"REVISION_REQUEST",valid.content(),"{}","{}","{}","[]",1);
        var admission=new ChatTypedDeliberationStore.Admission("admission",scope,"key","digest","body","DISCUSSION","task_fixture",1,
                null,null,row.requestId(),1,100,"[\"turn\"]","[]","ADMITTED",0,0,1);
        return new ChatDeliveryRevisionService.BoundRevision(new ChatActionFinalService.BoundAction(row,admission,valid),basis);
    }
    @Test void partialReplacementKeepsOnlyUntargetedResultsNeverAnOldManifest() {
        var lineage=new ChatFormalDeliveryRetentionService.Lineage(bound(),null);
        var fresh=new ChatSelectedOutputFinalizationStore.Selection("new-request","new-step","new-output",hash(),"new","RESULT");
        var selections=service.retain(lineage,List.of(fresh));assertEquals(3,selections.size());
        assertEquals("keep-a",selections.get(0).formalArtifactSource().sourceArtifactId());
        assertEquals(2,selections.get(0).formalArtifactSource().sourceArtifactVersion());
        assertEquals("keep-b",selections.get(1).formalArtifactSource().sourceArtifactId());assertEquals(fresh,selections.get(2));
        assertThrows(UnsupportedOperationException.class,()->selections.clear());
    }
    @Test void trustedNativeInputUsesFixedFormalBytesWithoutInventingPublicSelector() {
        when(contents.readContentForTaskOwner("0","client","owner","task_fixture","target",1))
                .thenReturn(new AgentTaskArtifactContentDTO("target",1,hash(),(long)BYTES.length,"text/plain",BYTES));
        var catalog=service.prepareRevisionSources(bound());assertEquals(1,catalog.size());var source=catalog.getFirst();
        assertEquals("FORMAL_ARTIFACT",source.get("sourceKind"));assertEquals(hash(),source.get("contentHash"));
        assertFalse(source.containsKey("selector"));assertTrue(source.get("sourceIdentity") instanceof Map);
        assertEquals(source.get("sourceRefId"),ChatTypedDeliberationContextService.sourceRefId(
                new ChatTypedDeliberationContextService.Scope("0","owner","client","7",1),source));
        verify(contents,never()).readContentForTaskOwner(anyString(),anyString(),anyString(),anyString(),eq("manifest"),anyInt());
    }
    @Test void missingOrChangedFixedBytesRejectWholePreparation() {
        assertThrows(ChatDeliveryReviewContract.SourceUnavailable.class,()->service.prepareRevisionSources(bound()));
        when(contents.readContentForTaskOwner("0","client","owner","task_fixture","target",1))
                .thenReturn(new AgentTaskArtifactContentDTO("target",1,hash(),1L,"text/plain",new byte[]{0}));
        assertThrows(ChatDeliveryReviewContract.SourceUnavailable.class,()->service.prepareRevisionSources(bound()));
    }
    @Test void bothOutboxIdentifiersFit64CharacterColumnsAndRemainDomainSeparated() {
        var row=bound().parent().outcome();var revision=ChatDeliveryRevisionService.eventId(row);var delivery=ChatDeliverySubmissionService.eventId(row);
        assertEquals(64,revision.length());assertEquals(64,delivery.length());assertNotEquals(revision,delivery);
    }

    ChatFormalDeliveryRetentionService.Lineage proofLineage(List<String> targets,String operation) {
        var b=bound(targets);
        var receipt=new ChatDeliveryReviewContract.Receipt(1,operation,"task_fixture","delivery","REQUEST_CHANGES","COMMITTED","2",
                "RUNNING","8","CHANGES_REQUESTED","1","child",b.parent().outcome().requestId(),"7",null,false);
        return new ChatFormalDeliveryRetentionService.Lineage(b,receipt);
    }
    void producer(String request,List<String> targets,ChatFormalDeliveryRetentionService.Lineage lineage,ChatFormalDeliveryRetentionService checked) {
        var bound=lineage.revision();var row=bound.parent().outcome();
        var sources=targets.stream().map(id->{var item=bound.basis().items().stream().filter(x->id.equals(x.artifactId())).findFirst().orElseThrow();
            return Map.<String,Object>of("sourceKind","FORMAL_ARTIFACT","contentMimeType","text/plain","contentHash",item.contentHash(),
                    "sourceIdentity",Map.of("kind","FORMAL_ARTIFACT","sourceDeliveryId","delivery","sourceRequestId",row.requestId(),
                            "parentFinalDigest",row.finalDigest(),"sourceArtifactId",id,"sourceArtifactVersion",item.artifactVersion()));}).toList();
        when(typed.findAdmissionByRequest(row.scope(),request)).thenReturn(new ChatTypedDeliberationStore.Admission("admission-"+request,row.scope(),
                "key","digest","body","DISCUSSION",row.taskId(),1,row.outcomeId(),null,request,1,100,"[]",
                ChatDeliveryReviewContract.json(sources),"ADMITTED",0,0,1));
        doReturn(lineage).when(checked).lineage(row.scope(),row.taskId(),request);
    }
    ChatSelectedOutputFinalizationStore.Selection selection(String request) {
        return new ChatSelectedOutputFinalizationStore.Selection(request,"step-"+request,"output",hash(),"replacement","RESULT");
    }
    @Test void exactIndependentTargetInputsCanReplaceMultipleItemsWithoutOrderingInference() {
        var lineage=proofLineage(List.of("target","keep-a"),"operation");var checked=spy(service);var scope=lineage.revision().parent().outcome().scope();
        producer("one",List.of("target"),lineage,checked);producer("two",List.of("keep-a"),lineage,checked);
        assertDoesNotThrow(()->checked.requireReplacementProof(scope,"task_fixture",lineage,List.of(selection("two"),selection("one"))));
        assertThrows(ChatDeliveryReviewContract.SourceUnavailable.class,()->checked.requireReplacementProof(scope,"task_fixture",lineage,List.of(selection("one"))));
        assertThrows(ChatDeliveryReviewContract.SourceUnavailable.class,()->checked.requireReplacementProof(scope,"task_fixture",lineage,List.of(selection("one"),selection("one"))));
    }
    @Test void ambiguousInputsOrDifferentCommittedRevisionCannotClaimAReplacement() {
        var lineage=proofLineage(List.of("target","keep-a"),"operation");var checked=spy(service);var scope=lineage.revision().parent().outcome().scope();
        producer("ambiguous",List.of("target","keep-a"),lineage,checked);
        assertThrows(ChatDeliveryReviewContract.SourceUnavailable.class,()->checked.requireReplacementProof(scope,"task_fixture",lineage,List.of(selection("ambiguous"))));
        producer("foreign",List.of("target"),proofLineage(List.of("target"),"other-operation"),checked);
        assertThrows(ChatDeliveryReviewContract.SourceUnavailable.class,()->checked.requireReplacementProof(scope,"task_fixture",lineage,List.of(selection("foreign"))));
    }
}
