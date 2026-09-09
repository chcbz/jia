package cn.jia.agent.output.service;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.output.OutputSourceAccessMode;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOutputSourceAuthorizerTest extends BaseMockTest {
    @Mock AgentTaskCollaborationAccessService accessService;

    @Test
    void locksTaskAndExactMemberForEveryAuthorization() {
        when(accessService.resolveMemberAccessForUpdate(
                "owner", "client", "task-1", "agent-1"))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        TaskOutputSourceAuthorizer authorizer =
                new TaskOutputSourceAuthorizer(accessService);

        OutputSourceAuthorization result = authorizer.lockAndAuthorize(
                "owner", "client", "task-1", "agent-1");

        assertEquals("owner", result.ownerJiacn());
        verify(accessService).resolveMemberAccessForUpdate(
                "owner", "client", "task-1", "agent-1");
        verify(accessService, never()).resolveMemberAccess(
                "owner", "client", "task-1", "agent-1");
    }

    @Test
    void failsClosedForReadOnlyAndByteVariantInputs() {
        when(accessService.resolveMemberAccessForUpdate(
                "owner", "client", "task-1", "agent-1"))
                .thenReturn(AgentTaskAccessLevel.READ_ONLY);
        TaskOutputSourceAuthorizer authorizer =
                new TaskOutputSourceAuthorizer(accessService);

        assertThrows(OutputAuthorizationException.class,
                () -> authorizer.lockAndAuthorize(
                        "owner", "client", "task-1", "agent-1"));
        assertThrows(OutputAuthorizationException.class,
                () -> authorizer.lockAndAuthorize(
                        "Owner", "client", " task-1", "agent-1"));

        OutputSourceAuthorization historical = authorizer.lockAndAuthorize(
                "owner", "client", "task-1", "agent-1",
                OutputSourceAccessMode.RECEIPT_READ);
        assertEquals(false, historical.writable());
    }

    @Test
    void receiptReadStillRejectsMembersWithoutReadAccess() {
        when(accessService.resolveMemberAccessForUpdate(
                "owner", "client", "task-1", "agent-1"))
                .thenReturn(AgentTaskAccessLevel.NONE);

        assertThrows(OutputAuthorizationException.class,
                () -> new TaskOutputSourceAuthorizer(accessService).lockAndAuthorize(
                        "owner", "client", "task-1", "agent-1",
                        OutputSourceAccessMode.RECEIPT_READ));
    }
}
