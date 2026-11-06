package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.chat.service.ChatDeliveryReviewContract.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatTaskDeliveryReviewReadServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final AgentTaskFormalDeliveryReadService deliveries=mock(AgentTaskFormalDeliveryReadService.class);
    final ChatSelectedOutputFinalizationStore submissions=mock(ChatSelectedOutputFinalizationStore.class);
    final ChatTaskDeliveryReviewReadService reads=new ChatTaskDeliveryReviewReadService(jdbc,deliveries,submissions);
    final Scope scope=new Scope("0","client","owner");
    AgentTaskFormalDeliveryViewDTO batch() {
        var b=ChatDeliveryReviewContractTest.batch(); b.setManifestArtifactId("artifact_manifest"); b.setManifestArtifactVersion(1); b.setSubmittedAt(123L); return b;
    }
    @Test void displayMetadataPassesThroughWithoutChangingSourceOrBatchDigest() {
        var b = batch(); String before = ChatDeliveryReviewContract.batchDigest("task_fixture", b);
        b.getItems().getFirst().setDisplay(new cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO.Display("report.txt", "text/plain", 12L, null));
        var result = reads.sourceItems(scope, "task_fixture", b);
        assertEquals("report.txt", result.getFirst().display().title());
        assertEquals(before, ChatDeliveryReviewContract.batchDigest("task_fixture", b));
        assertEquals("SOURCE_UNAVAILABLE", result.getFirst().unavailableReason());
    }
    @Test void unresolvableResultIsVisibleButNeverAcquiresWriteEligibility() {
        var b=batch(); var items=reads.sourceItems(scope,"task_fixture",b);
        assertEquals(2,items.size()); assertEquals("SOURCE_UNAVAILABLE",items.getFirst().unavailableReason());
        assertEquals("MANIFEST",items.getLast().displayRole()); assertNull(items.getLast().unavailableReason());
        assertFalse(ChatTaskDeliveryReviewReadService.sourcesComplete(items));
        verify(jdbc).query(contains("agent_selected_output_finalization"),any(RowMapper.class),eq("0"),eq("owner"),eq("client"),eq("task_fixture"),eq("delivery_fixture"));
    }
    @Test void exactCommittedWorkspaceMappingDoesNotExposeAStorageUri() throws Exception {
        when(jdbc.query(contains("SELECT o.workspace_file_id"),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            RowMapper<?> mapper=invocation.getArgument(1); ResultSet row=mock(ResultSet.class);
            when(row.getString(1)).thenReturn("file-1"); when(row.getInt(2)).thenReturn(1);
            return List.of(mapper.mapRow(row,0));
        });
        var result=reads.sourceItems(scope,"task_fixture",batch());
        assertTrue(ChatTaskDeliveryReviewReadService.sourcesComplete(result));
        assertEquals(java.util.Map.of("kind","WORKSPACE_FILE","fileId","file-1","version","1"),result.getFirst().source());
        assertFalse(ChatDeliveryReviewContract.json(result).contains("storageUri"));
    }
    @Test void retainedSourceProjectionUsesPersistedV2DigestAndExactHistoricalVersion() throws Exception {
        var batch=batch();var result=batch.getItems().getFirst();var manifest=batch.getItems().getLast();
        var source=new cn.jia.agent.service.SelectedOutputFinalizationDigest.FormalArtifactSource("old-delivery","old-artifact",2);
        var selected=new ChatSelectedOutputFinalizationStore.Selection(null,null,null,result.getContentHash(),"Retained",result.getPurpose(),null,source);
        String digest=cn.jia.agent.service.SelectedOutputFinalizationDigest.requestV2("task_fixture",7,1,"123","submit",List.of(
                new cn.jia.agent.service.SelectedOutputFinalizationDigest.Selection(null,null,null,result.getContentHash(),"Retained",result.getPurpose(),null,source)));
        var operation=new ChatSelectedOutputFinalizationStore.Operation("op","task_fixture","original-key",digest,"123",7,1,"submit","completed","SUBMITTED",2,
                batch.getDeliveryId(),"submitted","reviewing",9,null,false,List.of(selected),2);
        when(submissions.findByOperation("0","owner","client","task_fixture","op")).thenReturn(operation);
        when(jdbc.query(contains("FROM agent_selected_output_finalization"),any(RowMapper.class),any(Object[].class))).thenAnswer(i->{
            RowMapper<?> mapper=i.getArgument(1);ResultSet row=mock(ResultSet.class);
            when(row.getString(1)).thenReturn("op");when(row.getString(2)).thenReturn(batch.getManifestArtifactId());
            when(row.getString(3)).thenReturn(ChatDeliveryReviewContract.json(List.of(
                    java.util.Map.of("id",result.getArtifactId(),"version",result.getArtifactVersion(),"hash",result.getContentHash(),"purpose",result.getPurpose()),
                    java.util.Map.of("id",manifest.getArtifactId(),"version",manifest.getArtifactVersion(),"hash",manifest.getContentHash(),"purpose",manifest.getPurpose()))));
            return List.of(mapper.mapRow(row,0));
        });
        var items=reads.sourceItems(scope,"task_fixture",batch);
        assertEquals(java.util.Map.of("kind","FORMAL_ARTIFACT","sourceDeliveryId","old-delivery","sourceArtifactId","old-artifact","sourceArtifactVersion","2"),items.getFirst().source());
        assertTrue(ChatTaskDeliveryReviewReadService.sourcesComplete(items));
        assertNull(items.getLast().source());assertFalse(ChatDeliveryReviewContract.json(items).contains("requestId"));
    }
    @Test void currentReadHasNoCountOrHistoricalCatalogEvenWithManyHistoricalBatches() {
        when(submissions.task("0","owner","client","task_fixture")).thenReturn(new ChatSelectedOutputFinalizationStore.TaskFact("running",7));
        var context=reads.read(scope,"task_fixture");
        assertEquals(2,context.schemaVersion()); assertTrue(context.history().isEmpty()); assertNull(context.currentDelivery());
        verify(deliveries).currentForTaskOwner("0","client","owner","task_fixture",false);
        verify(deliveries,never()).listForTaskOwner(anyString(),anyString(),anyString(),anyString());
        verify(jdbc,never()).queryForObject(contains("COUNT"),eq(Long.class),any(Object[].class));
    }
    @Test void explicitHistoryPageIsReadOnlyAndPreservesCursor() {
        var b=batch();b.setSummary("history");
        when(deliveries.historyForTaskOwner("0","client","owner","task_fixture",101L)).thenReturn(
            new AgentTaskFormalDeliveryReadService.HistoryPage(List.of(b),"81",true));
        var page=reads.history(scope,"task_fixture",101L);
        assertEquals("81",page.nextCursor());assertTrue(page.hasMore());assertEquals(List.of("VIEW"),page.items().getFirst().allowedActions());
        verify(deliveries,never()).currentForTaskOwner(anyString(),anyString(),anyString(),anyString(),anyBoolean());
        verifyNoInteractions(submissions);
    }
    @Test void displaySourcesUsePageQueriesNotPerItemRevalidation() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call -> {
            String sql=call.getArgument(0); Object[] args=(Object[])call.getRawArguments()[2];
            assertEquals(sql.chars().filter(c -> c=='?').count(),args.length);
            assertTrue(sql.contains(" IN (")); assertFalse(sql.contains("FOR UPDATE"));
            return List.of();
        });
        var window = java.util.stream.IntStream.range(0,20).mapToObj(i -> { var b=batch(); b.setDeliveryId("d-"+i); return b; }).toList();
        var result = reads.displayItems(scope,"task_fixture",window);
        assertEquals(20,result.size());
        assertTrue(result.values().stream().allMatch(items -> "SOURCE_UNAVAILABLE".equals(items.getFirst().unavailableReason())));
        // No promoted rows => only promotion lookup plus workspace lookup, regardless of window size.
        verify(jdbc,times(2)).query(anyString(),any(RowMapper.class),any(Object[].class));
        verifyNoInteractions(submissions,deliveries);
    }
    @Test void committedPromotionDisplayUsesSelectedRowsWithoutReplayingItsReceipt() throws Exception {
        var b=batch(); var item=b.getItems().getFirst();
        when(jdbc.query(contains("SELECT p.delivery_id"),any(RowMapper.class),any(Object[].class))).thenAnswer(i -> {
            RowMapper<?> mapper=i.getArgument(1);ResultSet r=mock(ResultSet.class);
            when(r.getString(1)).thenReturn(b.getDeliveryId());when(r.getString(2)).thenReturn("op");
            when(r.getString(3)).thenReturn(b.getManifestArtifactId());
            when(r.getString(4)).thenReturn(ChatDeliveryReviewContract.json(List.of(java.util.Map.of("id",item.getArtifactId(),"version",1,"hash",item.getContentHash(),"purpose",item.getPurpose()))));
            return List.of(mapper.mapRow(r,0));
        });
        when(jdbc.query(contains("SELECT i.operation_id"),any(RowMapper.class),any(Object[].class))).thenAnswer(i -> {
            RowMapper<?> mapper=i.getArgument(1);ResultSet r=mock(ResultSet.class);
            when(r.getString(1)).thenReturn("op");when(r.getInt(2)).thenReturn(0);when(r.getString(3)).thenReturn(item.getContentHash());
            when(r.getString(4)).thenReturn(item.getPurpose());when(r.getString(5)).thenReturn("OUTPUT");
            when(r.getString(6)).thenReturn("request");when(r.getString(7)).thenReturn("step");when(r.getString(8)).thenReturn("output");
            return List.of(mapper.mapRow(r,0));
        });
        var result=reads.displayItems(scope,"task_fixture",List.of(b)).get(b.getDeliveryId());
        assertEquals(java.util.Map.of("kind","CONVERSATION_OUTPUT","requestId","request","stepId","step","outputId","output"),result.getFirst().source());
        assertTrue(ChatTaskDeliveryReviewReadService.sourcesComplete(result));
        verify(jdbc,times(3)).query(anyString(),any(RowMapper.class),any(Object[].class));
        verifyNoInteractions(submissions,deliveries);
    }
    @Test void ownerAuthorizationPrecedesEveryAssociatedRead() {
        when(submissions.task("0","owner","client","task_fixture")).thenThrow(new ChatSelectedOutputFinalizationStore.Missing());
        assertThrows(ChatSelectedOutputFinalizationStore.Missing.class,()->reads.read(scope,"task_fixture"));
        verifyNoInteractions(jdbc,deliveries);
        when(deliveries.historyForTaskOwner("0","client","owner","task_fixture",null)).thenThrow(new ChatSelectedOutputFinalizationStore.Missing());
        assertThrows(ChatSelectedOutputFinalizationStore.Missing.class,()->reads.history(scope,"task_fixture",null));
        verifyNoInteractions(jdbc);
    }
    @Test void immutableReceiptMustMatchItsPersistedOperationAndCannotClaimSubmissionIsAcceptance() {
        var receipt=new Receipt(1,"review-1","task","delivery","ACCEPT","COMMITTED","2","COMPLETED","8","ACCEPTED","1",null,null,null,null,false);
        var operation=new ChatTaskDeliveryReviewStore.Operation("review-1","task","review-key","a".repeat(64),"delivery","COMMITTED",2,ChatDeliveryReviewContract.json(receipt));
        assertEquals(receipt,ChatTaskDeliveryReviewStore.committedReceipt(operation));
        assertThrows(Storage.class,()->ChatDeliveryReviewContract.receipt(ChatDeliveryReviewContract.json(receipt).replace("ACCEPTED","SUBMITTED")));
        assertThrows(Conflict.class,()->ChatTaskDeliveryReviewStore.committedReceipt(new ChatTaskDeliveryReviewStore.Operation("other","task","review-key","a".repeat(64),"delivery","COMMITTED",2,operation.receiptJson())));
    }
}
