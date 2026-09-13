package cn.jia.agent.config;

import cn.jia.agent.api.AgentWorkItemReassignmentController;
import cn.jia.agent.dao.AgentHallCommandTransportDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.AgentWorkItemReassignmentDao;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.exception.AgentWorkItemReassignmentException;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.AgentWorkItemReassignmentService;
import cn.jia.agent.service.impl.AgentWorkItemReassignmentServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual conditional transport + constructor-injected service + HTTP facade.
 * No production DB, Rabbit connection, credentials or lifecycle operation.
 */
class AgentWorkItemReassignmentStartupTest {
    private static final String ACTOR = "agt_cccccccccccccccccccccccccccccccc";
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withBean(AgentWorkItemReassignmentDao.class,
                    () -> mock(AgentWorkItemReassignmentDao.class))
            .withBean(AgentTaskMemberDao.class, () -> mock(AgentTaskMemberDao.class))
            .withBean(AgentTaskWorkItemDao.class, () -> mock(AgentTaskWorkItemDao.class))
            .withBean(AgentTaskMutationTransaction.class,
                    () -> mock(AgentTaskMutationTransaction.class))
            .withBean(AgentIdentityService.class, () -> mock(AgentIdentityService.class))
            .withBean(AgentService.class, () -> mock(AgentService.class))
            .withBean(AgentTaskEventWriter.class, () -> mock(AgentTaskEventWriter.class))
            .withBean(AgentWorkItemLeaseService.class,
                    () -> mock(AgentWorkItemLeaseService.class))
            .withBean(AgentHallCommandTransportDao.class,
                    () -> mock(AgentHallCommandTransportDao.class))
            .withBean(AgentCommandTransportMapper.class,
                    () -> mock(AgentCommandTransportMapper.class))
            .withBean(AgentTaskCollaborationAccessService.class,
                    () -> mock(AgentTaskCollaborationAccessService.class))
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(AgentRabbitSafetyConfiguration.class,
                    AgentCommandTransportWriterConfiguration.class,
                    AgentWorkItemReassignmentServiceImpl.class,
                    AgentWorkItemReassignmentController.class);

    @Test
    void absentOutboxStartsFullFacadeAndRejectsAllOperationsWithoutSideEffects() {
        assertDisabled(RUNNER);
    }

    @Test
    void explicitlyDisabledOutboxStartsFullFacadeAndRejectsAllOperationsWithoutSideEffects() {
        assertDisabled(RUNNER.withPropertyValues("agent.command-outbox.enabled=false"));
    }

    @Test
    void enabledDbShadowInjectsRealWriterWithoutEnablingRabbit() {
        RUNNER.withPropertyValues("agent.command-outbox.enabled=true").run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(AgentWorkItemReassignmentController.class));
            assertSame(context.getBean(AgentCommandTransportWriter.class),
                    ReflectionTestUtils.getField(
                            context.getBean(AgentWorkItemReassignmentService.class), "commandWriter"));
            assertFalse(context.containsBean("agentRabbitConnectionFactory"));
        });
    }

    private static void assertDisabled(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("agentCommandTransportWriter"));
            assertFalse(context.containsBean("agentCommandMailboxService"));
            assertFalse(context.containsBean("agentRabbitConnectionFactory"));
            AgentWorkItemReassignmentService service =
                    context.getBean(AgentWorkItemReassignmentService.class);
            assertEquals(AgentWorkItemReassignmentException.Reason.TRANSPORT_DISABLED,
                    assertThrows(AgentWorkItemReassignmentException.class,
                            () -> service.reassign("tenant-a", "client-a", ACTOR, ACTOR,
                                    "task-1", "work-1", "reassign-key-0001", null)).getReason());
            assertEquals(AgentWorkItemReassignmentException.Reason.TRANSPORT_DISABLED,
                    assertThrows(AgentWorkItemReassignmentException.class,
                            () -> service.readLease("tenant-a", "client-a", ACTOR,
                                    "task-1", "work-1", "receipt-1", null)).getReason());
            assertEquals(AgentWorkItemReassignmentException.Reason.TRANSPORT_DISABLED,
                    assertThrows(AgentWorkItemReassignmentException.class,
                            () -> service.startLease("tenant-a", "client-a", ACTOR,
                                    "task-1", "work-1", "receipt-1", null)).getReason());
            assertEquals(AgentWorkItemReassignmentException.Reason.TRANSPORT_DISABLED,
                    assertThrows(AgentWorkItemReassignmentException.class,
                            () -> service.heartbeatLease("tenant-a", "client-a", ACTOR,
                                    "task-1", "work-1", "receipt-1", null)).getReason());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    context.getBean(AgentWorkItemReassignmentController.class)).build();
            String base = "/agent/tasks/task-1/work-items/work-1/reassignments";
            // Disabled transport must not bypass authentication or target-subject binding.
            mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(base + "/receipt-1/lease")
                            .queryParam("actorAgentId", "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                            .principal(principal()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            for (String suffix : List.of("", "/receipt-1/lease",
                    "/receipt-1/lease/start", "/receipt-1/lease/heartbeat")) {
                mvc.perform(post(base + suffix)
                                .header("Idempotency-Key", "reassign-key-0001")
                                .queryParam("actorAgentId", ACTOR).principal(principal())
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("WORK_ITEM_REASSIGNMENT_UNAVAILABLE"));
            }
            verifyNoInteractions(context.getBean(AgentWorkItemReassignmentDao.class),
                    context.getBean(AgentTaskMemberDao.class),
                    context.getBean(AgentTaskWorkItemDao.class),
                    context.getBean(AgentTaskMutationTransaction.class),
                    context.getBean(AgentIdentityService.class),
                    context.getBean(AgentService.class),
                    context.getBean(AgentTaskEventWriter.class),
                    context.getBean(AgentWorkItemLeaseService.class),
                    context.getBean(AgentHallCommandTransportDao.class),
                    context.getBean(AgentCommandTransportMapper.class));
        });
    }

    private static JwtAuthenticationToken principal() {
        Jwt jwt = Jwt.withTokenValue("offline-fixture-not-a-credential")
                .header("alg", "RS256").subject(ACTOR)
                .claim("jiacn", "tenant-a").claim("client_id", "client-a")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z")).build();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("agent-work-item-reassign")));
    }
}
