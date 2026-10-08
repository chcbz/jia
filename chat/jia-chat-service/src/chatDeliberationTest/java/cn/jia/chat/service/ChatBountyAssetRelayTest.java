package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatBountyAssetRelayTest {
    @Test void revokedSourceDoesNotBlockAnotherOwnersCommittedOutput() {
        var projector = mock(ChatBountyAssetProjector.class);
        var denied = new ChatBountyAssetProjector.Candidate("0", "owner-a", "client", "req-a", "step-a", "exec-a");
        var allowed = new ChatBountyAssetProjector.Candidate("0", "owner-b", "client", "req-b", "step-b", "exec-b");
        when(projector.pending(null, 64)).thenReturn(List.of(denied, allowed));
        when(projector.project(denied)).thenThrow(new PersonalWorkspaceExecutionService.Failure(
                PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED));
        try (var relay = new ChatBountyAssetRelay(projector)) {
            assertDoesNotThrow(relay::pollOnce);
            verify(projector).project(allowed);
            assertFalse(relay.isRunning()); // One explicit test poll does not start a background worker.
        }
    }
    @Test void unexpectedIntegrityFailureIsNotHiddenAsSuccess() {
        var projector = mock(ChatBountyAssetProjector.class);
        var candidate = new ChatBountyAssetProjector.Candidate("0", "owner", "client", "req", "step", "exec");
        when(projector.pending(null, 64)).thenReturn(List.of(candidate));
        when(projector.project(candidate)).thenThrow(new IllegalStateException("Committed output differs"));
        try (var relay = new ChatBountyAssetRelay(projector)) {
            assertThrows(IllegalStateException.class, relay::pollOnce);
        }
    }
}
