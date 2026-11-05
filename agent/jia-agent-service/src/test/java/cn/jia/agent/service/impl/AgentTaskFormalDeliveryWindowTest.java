package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentTaskFormalDeliveryWindowTest {
    final AgentTaskFormalDeliveryDao dao = mock(AgentTaskFormalDeliveryDao.class);
    final AgentTaskMetaDao tasks = mock(AgentTaskMetaDao.class);
    final AgentTaskWorkItemDao work = mock(AgentTaskWorkItemDao.class);
    final AgentTaskFormalDeliveryReadServiceImpl service = new AgentTaskFormalDeliveryReadServiceImpl(dao, tasks, work);
    void authorize() {
        var task = new AgentTaskMetaEntity().setTaskId("task").setOwnerJiacn("owner").setTaskVersion(7L);
        task.setTenantId("0"); task.setClientId("client");
        when(tasks.findByTaskIdInOwnerScope("0", "client", "owner", "task")).thenReturn(task);
        var item = new AgentTaskWorkItemEntity().setTaskId("task").setWorkItemId("work").setOwnerJiacn("owner").setVersion(1L);
        item.setTenantId("0"); item.setClientId("client");
        when(work.findByTaskAndWorkItemId("0", "client", "owner", "task", "work")).thenReturn(item);
        when(dao.listItems(eq("0"), eq("client"), anyString())).thenAnswer(call -> {
            var row = new AgentTaskFormalDeliveryItemEntity().setDeliveryId(call.getArgument(2)).setArtifactId("artifact")
                .setArtifactVersion(1).setContentHash("a".repeat(64)).setPurpose("result").setItemOrder(0);
            row.setTenantId("0"); row.setClientId("client"); return List.of(row);
        });
    }
    AgentTaskFormalDeliveryEntity delivery(long revision, String state) {
        var d = new AgentTaskFormalDeliveryEntity().setTaskId("task").setWorkItemId("work").setDeliveryId("d-"+revision)
            .setRevision(revision).setState(state).setVersion("submitted".equals(state)?0L:1L).setProducerAgentId("agent")
            .setRunId("run").setSummary("result").setManifestArtifactId("manifest").setManifestArtifactVersion(1).setSubmittedAt(1L);
        d.setTenantId("0"); d.setClientId("client");
        if (!"submitted".equals(state)) d.setReviewedAt(2L);
        if ("changes_requested".equals(state)) d.setReviewReason("revise");
        return d;
    }
    @Test void authorizationPrecedesBothWindows() {
        assertThrows(AgentTaskCollaborationException.class, () -> service.currentForTaskOwner("0","client","owner","task",false));
        assertThrows(AgentTaskCollaborationException.class, () -> service.historyForTaskOwner("0","client","owner","task",null));
        verifyNoInteractions(dao, work);
    }
    @Test void currentUsesOnlySubmittedCandidatesOrAcceptedFallback() {
        authorize();
        when(dao.listTaskDeliveryWindow("0","client","task","submitted",null,2)).thenReturn(List.of(delivery(10001,"submitted")));
        assertEquals("d-10001",service.currentForTaskOwner("0","client","owner","task",false).getDeliveryId());
        verify(dao, never()).listTaskDeliveries(anyString(),anyString(),anyString(),anyInt());
        when(dao.listTaskDeliveryWindow("0","client","task","submitted",null,2)).thenReturn(List.of());
        when(dao.listTaskDeliveryWindow("0","client","task","accepted",null,1)).thenReturn(List.of(delivery(10000,"accepted")));
        assertNull(service.currentForTaskOwner("0","client","owner","task",false));
        assertEquals("d-10000",service.currentForTaskOwner("0","client","owner","task",true).getDeliveryId());
    }
    @Test void twoSubmittedCandidatesFailWithoutReadingTheirItems() {
        authorize();
        when(dao.listTaskDeliveryWindow("0","client","task","submitted",null,2)).thenReturn(List.of(delivery(2,"submitted"),delivery(1,"submitted")));
        assertThrows(AgentTaskCollaborationException.class, () -> service.currentForTaskOwner("0","client","owner","task",false));
        verify(dao, never()).listItems(anyString(),anyString(),anyString());
    }
    @Test void keysetTraverses205BatchesButHydratesOnlyExplicitPagesWithoutLookaheadItems() {
        authorize();
        var all = LongStream.rangeClosed(1,205).map(i -> 206-i).mapToObj(i -> delivery(i,"changes_requested")).toList();
        when(dao.listTaskDeliveryWindow(eq("0"),eq("client"),eq("task"),isNull(),nullable(Long.class),eq(21))).thenAnswer(call -> {
            Long before=call.getArgument(4);return all.stream().filter(d -> before==null || d.getRevision()<before).limit(21).toList();
        });
        var first=service.historyForTaskOwner("0","client","owner","task",null);
        assertEquals(20,first.items().size()); assertEquals("186",first.nextCursor()); assertTrue(first.hasMore());
        verify(dao, times(20)).listItems(anyString(),anyString(),anyString());
        verify(dao, never()).listItems("0","client","d-185");
        int count=20; var page=first;
        while(page.hasMore()) { page=service.historyForTaskOwner("0","client","owner","task",Long.valueOf(page.nextCursor()));count+=page.items().size(); }
        assertEquals(205,count);assertNull(page.nextCursor());
        verify(dao, times(205)).listItems(anyString(),anyString(),anyString());
        verify(dao, never()).listTaskDeliveries(anyString(),anyString(),anyString(),anyInt());
    }
}
