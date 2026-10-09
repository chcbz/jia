package cn.jia.chat.service;

import cn.jia.core.context.EsContext;
import cn.jia.test.BaseMockTest;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HumanSenderIdentityResolverTest extends BaseMockTest {
    @Mock
    UserService userService;

    @Test
    void restoresStrictlyReversibleRawUtf8NicknameOnlyInMemory() {
        UserEntity user = new UserEntity()
                .setJiacn("jia-1")
                .setUsername("fallback-user")
                .setNickname("é\u0099\u0088æ\u0083\u00A0è¶\u0085");
        when(userService.findByJiacn("jia-1")).thenReturn(user);

        ServerResolvedSender sender = resolver().resolve(context("jia-1", "web", "context-user"));

        assertEquals("user", sender.type());
        assertEquals("陈惠超", sender.displayName());
        assertEquals(DisplayNameSource.NICKNAME, sender.source());
        assertEquals("é\u0099\u0088æ\u0083\u00A0è¶\u0085", user.getNickname());
        verify(userService, never()).update(user);
    }

    @Test
    void irreversibleLegacyFragmentFallsBackWithoutGuessing() {
        UserEntity user = new UserEntity()
                .setJiacn("jia-1")
                .setUsername("server-user")
                .setNickname("éæ\u00A0è¶");
        when(userService.findByJiacn("jia-1")).thenReturn(user);

        ServerResolvedSender sender = resolver().resolve(context("jia-1", "web", "context-user"));

        assertEquals("server-user", sender.displayName());
        assertEquals(DisplayNameSource.USERNAME, sender.source());
    }

    @Test
    void preservesLegitimateWesternAccentsAndUsesContextFallbackOrder() {
        UserEntity user = new UserEntity()
                .setJiacn("jia-1")
                .setUsername("server-user")
                .setNickname("José");
        when(userService.findByJiacn("jia-1")).thenReturn(user);

        assertEquals("José", resolver().resolve(context("jia-1", "web", "context-user")).displayName());

        user.setNickname("\u0007");
        user.setUsername(" ");
        ServerResolvedSender fallback = resolver().resolve(context("jia-1", "web", "context-user"));
        assertEquals("context-user", fallback.displayName());
        assertEquals(DisplayNameSource.CONTEXT_USERNAME, fallback.source());
    }

    @Test
    void missingAuthenticatedScopeFailsClosedBeforeLookup() {
        assertThrows(IllegalStateException.class,
                () -> resolver().resolve(context("jia-1", " ", "context-user")));
        verify(userService, never()).findByJiacn("jia-1");
    }

    private HumanSenderIdentityResolver resolver() {
        return new HumanSenderIdentityResolver(userService);
    }

    private EsContext context(String jiacn, String clientId, String username) {
        EsContext context = new EsContext();
        context.setJiacn(jiacn);
        context.setClientId(clientId);
        context.setUsername(username);
        return context;
    }
}
