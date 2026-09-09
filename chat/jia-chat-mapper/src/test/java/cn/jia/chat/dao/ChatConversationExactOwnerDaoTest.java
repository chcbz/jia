package cn.jia.chat.dao;

import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

class ChatConversationExactOwnerDaoTest extends BaseMockTest {
    @Mock ChatConversationMapper mapper;

    @Test
    void canonicalPositiveDecimalIsRequiredAndLockFlagReachesMapper() {
        ChatConversationDaoImpl dao = new ChatConversationDaoImpl();
        ReflectionTestUtils.setField(dao, "baseMapper", mapper);

        dao.findExactOwnedById("owner", "client", "owner", "101", false);
        dao.findExactOwnedById("owner", "client", "owner", "101", true);

        verify(mapper).findExactOwnedById("owner", "client", "owner", 101L, false);
        verify(mapper).findExactOwnedById("owner", "client", "owner", 101L, true);
        for (String invalid : new String[] {"0", "-1", "01", "+1", "1 ", "1.0"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findExactOwnedById(
                            "owner", "client", "owner", invalid, true), invalid);
        }
    }
}
