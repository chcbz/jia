package cn.jia.chat.deliberation;

import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatConversationEventVersionTest {
    @Test void generatedSequenceIsTheWireVersionWithoutASecondWrite() throws Exception {
        var event = new ChatConversationEventEntity();
        assertNull(event.getEventVersion());
        event.setEventSequence(9007199254740993L);
        assertEquals(9007199254740993L, event.getEventVersion());
        var insert = ChatDeliberationMapper.class.getMethod("insertEvent", ChatConversationEventEntity.class)
                .getAnnotation(Insert.class);
        assertTrue(String.join(" ", insert.value()).contains("#{eventType},0,#{payloadJson}"));
        assertFalse(java.util.Arrays.stream(ChatDeliberationDao.class.getMethods())
                .anyMatch(method -> method.getName().equals("assignEventVersion")));
    }

    @Test void replayVersionAlwaysUsesItsOwnPersistedSequence() {
        var event = new ChatConversationEventEntity().setEventSequence(42L);
        assertEquals(42L, event.getEventVersion());
        event.setEventSequence(43L);
        assertEquals(43L, event.getEventVersion());
    }
}
