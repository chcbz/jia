package cn.jia.chat.dao;

import cn.jia.chat.mapper.ChatMessageMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatMessageReferenceWindowTest {
    @Test void referenceIdsUseExactlyTheHistoryScopeOrderAndLimitWithoutBodies() throws Exception {
        var parameters = new Class<?>[]{String.class, String.class, String.class, String.class, Long.class, int.class};
        String bodies = String.join(" ", ChatMessageMapper.class.getDeclaredMethod(
                "findExactOwnedConversationMessagesWithLimit", parameters).getAnnotation(Select.class).value());
        String ids = String.join(" ", ChatMessageMapper.class.getDeclaredMethod(
                "findExactOwnedConversationMessageIdsWithLimit", parameters).getAnnotation(Select.class).value());
        assertEquals(bodies.replace("SELECT m.*", "SELECT m.id"), ids);
        assertFalse(ids.contains("m.content"));
        assertFalse(ids.contains("m.metadata"));
    }
}
