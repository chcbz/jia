package cn.jia.chat.advisor;

import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LongTermMemoryAdvisorScopeTest {
    @Test void trustedClientAndOwnerReachStorageWithoutAnonymousFallback() {
        var repository = mock(MemoryRepository.class);
        var guard = mock(AgentTaskThreadMemoryGuard.class);
        var advisor = LongTermMemoryAdvisor.builder(repository).taskThreadMemoryGuard(guard).build();
        var request = mock(ChatClientRequest.class);
        when(request.context()).thenReturn(Map.of("jiacn", "alice", "clientId", "web-client"));
        when(request.prompt()).thenReturn(new Prompt("question"));
        when(repository.searchWithConversationBoost("web-client", "alice", "question", "default", 5, 0.75)).thenReturn(List.of());
        assertSame(request, advisor.before(request, null));
        verify(repository).searchWithConversationBoost("web-client", "alice", "question", "default", 5, 0.75);
    }
    @Test void absentPrivateIdentitySkipsPaidRetrieval() {
        var repository = mock(MemoryRepository.class);
        var guard = mock(AgentTaskThreadMemoryGuard.class);
        var advisor = LongTermMemoryAdvisor.builder(repository).taskThreadMemoryGuard(guard).build();
        for (Map<String,Object> context : List.<Map<String,Object>>of(Map.of(), Map.of("jiacn", "alice"),
                Map.of("clientId", "web-client"), Map.of("jiacn", "alice", "clientId", ""))) {
            var request = mock(ChatClientRequest.class); when(request.context()).thenReturn(context);
            assertSame(request, advisor.before(request, null));
        }
        verifyNoInteractions(repository, guard);
    }
}
