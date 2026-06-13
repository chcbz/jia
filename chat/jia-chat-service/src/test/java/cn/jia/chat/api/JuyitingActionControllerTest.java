package cn.jia.chat.api;

import cn.jia.chat.service.HallActionDispatchResult;
import cn.jia.chat.service.HallActionDispatcher;
import cn.jia.chat.service.HallActionIntent;
import cn.jia.chat.service.HallAgentMailboxItem;
import cn.jia.core.entity.JsonResult;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JuyitingActionControllerTest extends BaseMockTest {
    @Mock
    HallActionDispatcher dispatcher;

    @Test
    void dispatchesActionIntentThroughDispatcher() {
        HallActionIntent request = new HallActionIntent();
        request.setIntentId("intent-1");
        request.setActorAgentId("agent-linchong");
        when(dispatcher.dispatch(request)).thenReturn(
                new HallActionDispatchResult("intent-1", "agent-linchong", "dispatched", "dispatched"));
        JuyitingActionController controller = new JuyitingActionController(dispatcher);

        JsonResult<HallActionDispatchResult> result = controller.dispatch("intent-1", request);

        verify(dispatcher).dispatch(request);
        assertEquals("intent-1", result.getData().getIntentId());
        assertEquals("dispatched", result.getData().getStatus());
    }

    @Test
    void listsAgentMailboxItems() {
        HallAgentMailboxItem item = new HallAgentMailboxItem();
        item.setIntentId("intent-2");
        item.setAgentId("agent-linchong");
        when(dispatcher.mailbox("agent-linchong")).thenReturn(List.of(item));
        JuyitingActionController controller = new JuyitingActionController(dispatcher);

        JsonResult<List<HallAgentMailboxItem>> result = controller.mailbox("agent-linchong");

        assertEquals(1, result.getData().size());
        assertEquals("intent-2", result.getData().getFirst().getIntentId());
    }
}
