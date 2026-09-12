package cn.jia.chat.output;

import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatOutputDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class ConversationOutputVersionProviderTest extends BaseMockTest {
    @Mock ChatConversationDao conversationDao;
    @Mock ChatOutputDao outputDao;

    @Test
    void deletedConversationReturnedByDaoStillFailsClosed() {
        ChatConversationEntity deleted = new ChatConversationEntity()
                .setId(101L)
                .setJiacn("owner")
                .setDeletedAt(10L);
        deleted.setTenantId("owner");
        deleted.setClientId("client");
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "101", false)).thenReturn(deleted);
        ConversationOutputVersionProvider provider =
                new ConversationOutputVersionProvider(conversationDao, outputDao);

        OutputDeliveryException denied = assertThrows(OutputDeliveryException.class,
                () -> provider.requireOwner("owner", "client", "owner", "101", false));

        assertEquals("OUTPUT_NOT_FOUND", denied.code());
        assertEquals(404, denied.status());
    }
}
