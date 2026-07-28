package cn.jia.chat.service;

import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class AgentTaskThreadMemoryGuardTest extends BaseMockTest {
    @Mock AgentTaskThreadDao taskThreadDao;

    @Test
    void boundTaskMemoryIsExcludedForEveryCallerIncludingCrossClientSearches() {
        when(taskThreadDao.findAnyByConversationId("77"))
                .thenReturn(new AgentTaskThreadEntity().setConversationId("77"));
        AgentTaskThreadMemoryGuard guard = new AgentTaskThreadMemoryGuard(taskThreadDao);
        MemoryDocument protectedDoc = document("77", "secret team progress");
        MemoryDocument publicDoc = document("88", "public project note");

        List<MemoryDocument> visible = guard.excludeProtected(List.of(protectedDoc, publicDoc));

        assertEquals(List.of(publicDoc), visible);
    }

    @Test
    void lookupFailureAndMalformedConversationIdFailClosed() {
        when(taskThreadDao.findAnyByConversationId("77"))
                .thenThrow(new IllegalStateException("database unavailable"));
        AgentTaskThreadMemoryGuard guard = new AgentTaskThreadMemoryGuard(taskThreadDao);

        assertTrue(guard.isProtectedConversation("77"));
        assertTrue(guard.isProtectedConversation(" 77"));
        assertTrue(guard.isProtectedConversation("77\0"));
    }

    private MemoryDocument document(String conversationId, String content) {
        MemoryDocument document = new MemoryDocument();
        document.setConversationId(conversationId);
        document.setContent(content);
        return document;
    }
}
