package cn.jia.agent.api;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.service.AgentTaskEventReplayService;
import cn.jia.agent.service.AgentTaskEventReplayService.DurableEvent;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplayBackpressureException;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplayCapacityException;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplaySignal;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncReason;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncRequired;
import cn.jia.agent.service.AgentTaskEventReplayService.TaskScope;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.agent.service.AgentTaskWorkspaceService.AuthorizedSubject;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskEventStreamControllerTest extends BaseMockTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final TaskScope SCOPE = new TaskScope(TENANT, CLIENT, TASK);

    @Mock AgentTaskWorkspaceService workspaceService;
    @Mock AgentTaskEventReplayService replayService;

    private AgentTaskEventStreamController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new AgentTaskEventStreamController(workspaceService, replayService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(workspaceService.authorize(TENANT, CLIENT, TASK, ACTOR))
                .thenReturn(subject());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        EsContextHolder.setContext(new EsContext());
    }

    @Test
    void directJwtClaimsAreSoleAuthorityAndSuccessHasFrozenHeadersAndFrame() throws Exception {
        EsContext poisoned = new EsContext();
        poisoned.setJiacn("cookie-tenant");
        poisoned.setClientId("cookie-client");
        EsContextHolder.setContext(poisoned);
        DurableEvent event = taskCreated(9_007_199_254_740_993L);
        when(replayService.replay(SCOPE, 0L)).thenReturn(Flux.just(event));

        MvcResult result = complete(mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                .queryParam("actorAgentId", ACTOR)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .principal(authenticate(TENANT, CLIENT)))
                .andExpect(request().asyncStarted())
                .andReturn());

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("private, no-store", result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("no", result.getResponse().getHeader("X-Accel-Buffering"));
        assertNull(result.getResponse().getHeader(HttpHeaders.CONNECTION));
        assertTrue(result.getResponse().getContentType()
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.matches("(?s)^event: ?task_event\\r?\\n"
                + "id: ?9007199254740993\\r?\\n"
                + "data: ?\\{.*}\\r?\\n\\r?\\n$"), body);
        assertTrue(body.contains("\"version\":\"9007199254740993\""), body);
        assertTrue(body.contains("\"occurredAt\":\"1234\""), body);
        assertFalse(body.contains("tenant-a"), body);
        assertFalse(body.contains("client-a"), body);
        assertFalse(body.contains("eventJson"), body);
        verify(workspaceService).authorize(TENANT, CLIENT, TASK, ACTOR);
        verify(replayService).replay(SCOPE, 0L);
    }

    @Test
    void authenticationPrecedesSyntaxAndMissingOrInvalidDirectClaimsAre401Or403()
            throws Exception {
        mvc.perform(get("/agent/tasks/{taskId}/events", " padded ")
                        .queryParam("actorAgentId", " padded "))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        Jwt invalid = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", TENANT)
                .claim("client_id", 123)
                .claim("sub", "fallback-sub")
                .claim("clientId", "fallback-client")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .principal(new JwtAuthenticationToken(invalid, List.of())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verifyNoInteractions(replayService);
    }

    @Test
    void directJwtClaimSyntaxIsByteExactBoundedAndControlFree() throws Exception {
        for (String tenant : List.of("", " tenant", "tenant ", "bad\ntenant",
                "x".repeat(51))) {
            mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                            .queryParam("actorAgentId", ACTOR)
                            .principal(authenticateClaims(tenant, CLIENT)))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        for (String client : List.of("", " client", "client ", "bad\tclient",
                "x".repeat(51))) {
            mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                            .queryParam("actorAgentId", ACTOR)
                            .principal(authenticateClaims(TENANT, client)))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        verify(workspaceService, never()).authorize(any(), any(), any(), any());
        verifyNoInteractions(replayService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "00", "01", "+1", "-1", "1.0", "1e3",
            "١", "9223372036854775808", "10000000000000000000", "1,2"})
    void strictRawCursorGrammarRejectsEveryNonCanonicalForm(String cursor)
            throws Exception {
        mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .queryParam("sinceVersion", cursor)
                        .principal(authenticate(TENANT, CLIENT)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verify(workspaceService, never()).authorize(any(), any(), any(), any());
        verifyNoInteractions(replayService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "00", "01", "+1", "-1", "1.0", "1e3",
            "١", "9223372036854775808", "10000000000000000000", "1,2"})
    void strictRawHeaderCursorGrammarRejectsEveryNonCanonicalForm(String cursor)
            throws Exception {
        mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .header("Last-Event-ID", cursor)
                        .principal(authenticate(TENANT, CLIENT)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verify(workspaceService, never()).authorize(any(), any(), any(), any());
        verifyNoInteractions(replayService);
    }

    @Test
    void requiredActorAndRouteIdentifiersAreByteExactBoundedAndControlFree()
            throws Exception {
        JwtAuthenticationToken auth = authenticate(TENANT, CLIENT);
        for (String actor : List.of("", " padded", "padded ", "bad\nactor", "x".repeat(101))) {
            mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                            .queryParam("actorAgentId", actor).principal(auth))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        mvc.perform(get("/agent/tasks/{taskId}/events", " padded ")
                        .queryParam("actorAgentId", ACTOR).principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/{taskId}/events", TASK).principal(auth))
                .andExpect(status().isBadRequest());
        verify(workspaceService, never()).authorize(any(), any(), any(), any());
        verifyNoInteractions(replayService);
    }

    @Test
    void duplicateQueryRepeatedOrCommaJoinedHeaderAndDuplicateActorAre400()
            throws Exception {
        JwtAuthenticationToken auth = authenticate(TENANT, CLIENT);
        mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .queryParam("sinceVersion", "1", "2").principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                        .queryParam("actorAgentId", ACTOR, OTHER).principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .header("Last-Event-ID", "1", "2").principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .header("Last-Event-ID", "1,2").principal(auth))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(replayService);
    }

    @Test
    void independentlyValidatedQueryAndHeaderUseNumericMaximumIncludingLongBoundaries()
            throws Exception {
        when(replayService.replay(any(), anyLong())).thenAnswer(invocation -> Flux.just(
                new ResyncRequired(SCOPE, invocation.getArgument(1),
                        ResyncReason.CURSOR_AHEAD)));
        JwtAuthenticationToken auth = authenticate(TENANT, CLIENT);

        complete(mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                .queryParam("actorAgentId", ACTOR)
                .queryParam("sinceVersion", "9007199254740993")
                .header("Last-Event-ID", "7").principal(auth))
                .andExpect(request().asyncStarted()).andReturn());
        complete(mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                .queryParam("actorAgentId", ACTOR)
                .queryParam("sinceVersion", "7")
                .header("Last-Event-ID", "9223372036854775807").principal(auth))
                .andExpect(request().asyncStarted()).andReturn());

        verify(replayService).replay(SCOPE, 9_007_199_254_740_993L);
        verify(replayService).replay(SCOPE, Long.MAX_VALUE);
    }

    @ParameterizedTest
    @EnumSource(ResyncReason.class)
    void eachResyncWritesOneNoIdFrameThenDisposesAndCloses(ResyncReason reason)
            throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        when(replayService.replay(SCOPE, 0L)).thenReturn(Flux.concat(
                Flux.just(new ResyncRequired(SCOPE, 42L, reason)),
                Flux.<ReplaySignal>never()).doOnCancel(cancellations::incrementAndGet));

        MvcResult result = complete(mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                .queryParam("actorAgentId", ACTOR)
                .principal(authenticate(TENANT, CLIENT)))
                .andExpect(request().asyncStarted()).andReturn());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(1, body.lines().filter(line -> line.startsWith("event:")).count(), body);
        assertTrue(body.startsWith("event:resync_required")
                || body.startsWith("event: resync_required"), body);
        assertFalse(body.lines().anyMatch(line -> line.startsWith("id:")), body);
        assertTrue(body.contains("\"currentVersion\":\"42\""), body);
        assertTrue(body.contains("\"reason\":\""
                + reason.name().toLowerCase(java.util.Locale.ROOT) + "\""), body);
        assertEquals(1, cancellations.get());
    }

    @Test
    void ownRevocationDurableFrameIsSentThenLaterQueuedEventIsSuppressed()
            throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        DurableEvent revoked = memberRejected(5L, ACTOR);
        DurableEvent later = taskCreated(6L);
        when(replayService.replay(SCOPE, 0L)).thenReturn(Flux.concat(
                Flux.just(revoked, later), Flux.<ReplaySignal>never())
                .doOnCancel(cancellations::incrementAndGet));

        MvcResult result = complete(mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                .queryParam("actorAgentId", ACTOR)
                .principal(authenticate(TENANT, CLIENT)))
                .andExpect(request().asyncStarted()).andReturn());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("id:5") || body.contains("id: 5"), body);
        assertFalse(body.contains("id:6") || body.contains("id: 6"), body);
        assertEquals(1, body.lines().filter(line -> line.startsWith("event:")).count(), body);
        assertEquals(1, cancellations.get());
    }

    @Test
    void generic404BodyIsByteEqualAcrossAllHiddenSubjectCategories() throws Exception {
        when(workspaceService.authorize(TENANT, CLIENT, TASK, ACTOR)).thenThrow(
                new AgentTaskWorkspaceException(
                        AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN));
        byte[] expected = null;
        for (int index = 0; index < 4; index++) {
            MvcResult result = mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                            .queryParam("actorAgentId", ACTOR)
                            .principal(authenticate(TENANT, CLIENT)))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andReturn();
            if (expected == null) {
                expected = result.getResponse().getContentAsByteArray();
            } else {
                assertArrayEquals(expected, result.getResponse().getContentAsByteArray());
            }
        }
        verifyNoInteractions(replayService);
    }

    @Test
    void preFirstCapacityBackpressureProjectionAndShutdownFailuresAreSafe503()
            throws Exception {
        for (Flux<ReplaySignal> source : List.<Flux<ReplaySignal>>of(
                Flux.error(new ReplayCapacityException()),
                Flux.error(new ReplayBackpressureException()),
                Flux.just(taskCreatedWithJson(1L, "{\"credential\":\"secret\"}")),
                Flux.empty())) {
            when(replayService.replay(SCOPE, 0L)).thenReturn(source);
            MvcResult initial = mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                            .queryParam("actorAgentId", ACTOR)
                            .principal(authenticate(TENANT, CLIENT)))
                    .andExpect(request().asyncStarted()).andReturn();
            MvcResult result = complete(initial);
            assertEquals(503, result.getResponse().getStatus());
            assertEquals("private, no-store",
                    result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(body.contains("TASK_EVENTS_UNAVAILABLE"), body);
            assertFalse(body.contains("credential"), body);
            assertFalse(body.contains("secret"), body);
        }
    }

    @Test
    void postFirstFailureIsCleanEofWithoutJsonErrorOrSyntheticResync() throws Exception {
        when(replayService.replay(SCOPE, 0L)).thenReturn(Flux.concat(
                Flux.just(taskCreated(1L)),
                Flux.error(new ReplayBackpressureException())));
        MvcResult result = complete(mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                .queryParam("actorAgentId", ACTOR)
                .principal(authenticate(TENANT, CLIENT)))
                .andExpect(request().asyncStarted()).andReturn());
        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(1, body.lines().filter(line -> line.startsWith("event:")).count(), body);
        assertFalse(body.contains("TASK_EVENTS_UNAVAILABLE"), body);
        assertFalse(body.contains("resync_required"), body);
    }

    @Test
    void aclReturnsBeforeReplayAndNoTransactionExistsDuringSubscriptionOrSend() throws Exception {
        when(workspaceService.authorize(TENANT, CLIENT, TASK, ACTOR)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return subject();
        });
        when(replayService.replay(SCOPE, 0L)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return Flux.defer(() -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return Flux.just(taskCreated(1L));
            });
        });
        complete(mvc.perform(get("/agent/tasks/{taskId}/events", TASK)
                .queryParam("actorAgentId", ACTOR)
                .principal(authenticate(TENANT, CLIENT)))
                .andExpect(request().asyncStarted()).andReturn());
    }

    @Test
    void cancelTimeoutAndSendFailureDisposeExactlyOnceAndForbidLaterSend() throws Exception {
        AtomicInteger cancel = new AtomicInteger();
        AgentTaskEventStreamController.ManagedSseEmitter emitter =
                new AgentTaskEventStreamController.ManagedSseEmitter(30_000L);
        AgentTaskEventStreamController.StreamConnection connection =
                new AgentTaskEventStreamController.StreamConnection(emitter, subject());
        connection.start(Flux.<ReplaySignal>never().doOnCancel(cancel::incrementAndGet));
        emitter.complete();
        emitter.complete();
        connection.timeout();
        assertEquals(1, cancel.get());

        AtomicInteger timeoutCancel = new AtomicInteger();
        AgentTaskEventStreamController.ManagedSseEmitter timeoutEmitter =
                new AgentTaskEventStreamController.ManagedSseEmitter(30_000L);
        AgentTaskEventStreamController.StreamConnection timeoutConnection =
                new AgentTaskEventStreamController.StreamConnection(timeoutEmitter, subject());
        timeoutConnection.start(Flux.<ReplaySignal>never()
                .doOnCancel(timeoutCancel::incrementAndGet));
        timeoutConnection.timeout();
        timeoutConnection.timeout();
        assertEquals(1, timeoutCancel.get());

        AtomicInteger sendCancel = new AtomicInteger();
        FailingEmitter failing = new FailingEmitter(1);
        AgentTaskEventStreamController.StreamConnection failingConnection =
                new AgentTaskEventStreamController.StreamConnection(failing, subject());
        failingConnection.start(Flux.concat(Flux.just(taskCreated(1L), taskCreated(2L)),
                Flux.<ReplaySignal>never()).doOnCancel(sendCancel::incrementAndGet));
        assertEquals(1, failing.sendCount);
        assertEquals(1, sendCancel.get());
        assertInstanceOf(AgentTaskEventStreamController.StreamUnavailableException.class,
                failing.error);
        assertEquals(0, failing.completeCount);
    }

    @Test
    void concurrentSendAndTimeoutSerializeTerminalAndCleanupExactlyOnce()
            throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        BlockingEmitter emitter = new BlockingEmitter();
        AgentTaskEventStreamController.StreamConnection connection =
                new AgentTaskEventStreamController.StreamConnection(emitter, subject());
        Sinks.Many<ReplaySignal> sink = Sinks.many().multicast().directBestEffort();
        connection.start(sink.asFlux().doOnCancel(cancellations::incrementAndGet));

        AtomicReference<Throwable> senderFailure = new AtomicReference<>();
        Thread sender = new Thread(() -> {
            try {
                Sinks.EmitResult result = sink.tryEmitNext(taskCreated(1L));
                if (result.isFailure()) {
                    throw new AssertionError("first emit failed: " + result);
                }
            } catch (Throwable failure) {
                senderFailure.set(failure);
            }
        }, "c05-test-sender");
        AtomicReference<Throwable> timeoutFailure = new AtomicReference<>();
        Thread timeout = new Thread(() -> {
            try {
                connection.timeout();
            } catch (Throwable failure) {
                timeoutFailure.set(failure);
            }
        }, "c05-test-timeout");

        sender.start();
        assertTrue(emitter.sendEntered.await(2, TimeUnit.SECONDS));
        timeout.start();
        emitter.allowSend.countDown();
        sender.join(2_000L);
        timeout.join(2_000L);

        assertFalse(sender.isAlive());
        assertFalse(timeout.isAlive());
        assertNull(senderFailure.get());
        assertNull(timeoutFailure.get());
        assertEquals(1, emitter.sendCount.get());
        assertEquals(1, emitter.completeCount.get());
        assertEquals(1, cancellations.get());
        assertTrue(sink.tryEmitNext(taskCreated(2L)).isFailure());
        assertEquals(1, emitter.sendCount.get());
    }

    @Test
    void earliestCacheFilterCoversEventsSecurityResponses() throws Exception {
        AgentTaskWorkspaceCacheControlFilter filter = new AgentTaskWorkspaceCacheControlFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/agent/tasks/task-1/events");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));
        assertEquals(401, response.getStatus());
        assertEquals("private, no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    private MvcResult complete(MvcResult initial) throws Exception {
        return mvc.perform(asyncDispatch(initial)).andReturn();
    }

    private static JwtAuthenticationToken authenticate(String jiacn, String clientId) {
        JwtAuthenticationToken authentication = authenticateClaims(jiacn, clientId);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }

    private static JwtAuthenticationToken authenticateClaims(Object jiacn, Object clientId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", jiacn)
                .claim("client_id", clientId)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private static AuthorizedSubject subject() {
        return new AuthorizedSubject(TENANT, CLIENT, TASK, ACTOR, "worker", OTHER);
    }

    private static DurableEvent taskCreated(long version) {
        return taskCreatedWithJson(version,
                "{\"taskId\":\"task-1\",\"taskType\":\"agent_task\","
                        + "\"status\":\"assigned\",\"resultVersion\":1,"
                        + "\"createdAt\":1234}");
    }

    private static DurableEvent taskCreatedWithJson(long version, String json) {
        return new DurableEvent(SCOPE, version, "evt-" + version,
                TaskEventType.TASK_CREATED, "system", null, "task", TASK,
                json, 1234L);
    }

    private static DurableEvent memberRejected(long version, String memberId) {
        return new DurableEvent(SCOPE, version, "evt-member-" + version,
                TaskEventType.MEMBER_REJECTED, "system", null, "member", memberId,
                "{\"agentId\":\"" + memberId + "\",\"role\":\"worker\","
                        + "\"toStatus\":\"rejected\",\"resultVersion\":2}",
                1234L);
    }

    private static final class BlockingEmitter
            extends AgentTaskEventStreamController.ManagedSseEmitter {
        private final CountDownLatch sendEntered = new CountDownLatch(1);
        private final CountDownLatch allowSend = new CountDownLatch(1);
        private final AtomicInteger sendCount = new AtomicInteger();
        private final AtomicInteger completeCount = new AtomicInteger();

        private BlockingEmitter() {
            super(30_000L);
        }

        @Override
        public void send(SseEmitter.SseEventBuilder builder) throws IOException {
            sendCount.incrementAndGet();
            sendEntered.countDown();
            try {
                if (!allowSend.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release simulated send");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("simulated send interrupted", exception);
            }
        }

        @Override
        public void complete() {
            completeCount.incrementAndGet();
            super.complete();
        }
    }

    private static final class FailingEmitter
            extends AgentTaskEventStreamController.ManagedSseEmitter {
        private final int failAt;
        private int sendCount;
        private int completeCount;
        private Throwable error;

        private FailingEmitter(int failAt) {
            super(30_000L);
            this.failAt = failAt;
        }

        @Override
        public synchronized void send(SseEmitter.SseEventBuilder builder) throws IOException {
            sendCount++;
            if (sendCount >= failAt) {
                throw new IOException("simulated send failure");
            }
            super.send(builder);
        }

        @Override
        public void complete() {
            completeCount++;
            super.complete();
        }

        @Override
        public void completeWithError(Throwable error) {
            this.error = error;
            super.completeWithError(error);
        }
    }
}
