package cn.jia.agent.api;

import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentScenePhaseReportDTO;
import cn.jia.agent.entity.AgentScenePhaseResultDTO;
import cn.jia.agent.entity.AgentSceneSnapshotDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentSceneControllerTest {

    @Test
    void exposesExactMvcRoutesAndEventStreamContentType() throws Exception {
        RequestMapping root = AgentSceneController.class.getAnnotation(RequestMapping.class);
        assertEquals("/agent/scenes", root.value()[0]);

        Method snapshot = AgentSceneController.class.getMethod("snapshot", String.class);
        assertEquals("/{sceneId}/snapshot", snapshot.getAnnotation(GetMapping.class).value()[0]);

        Method events = AgentSceneController.class.getMethod(
                "events", String.class, Long.class, String.class);
        GetMapping eventsMapping = events.getAnnotation(GetMapping.class);
        assertEquals("/{sceneId}/events", eventsMapping.value()[0]);
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, eventsMapping.produces()[0]);
        RequestParam sinceVersion = events.getParameters()[1].getAnnotation(RequestParam.class);
        assertEquals("sinceVersion", sinceVersion.name());

        Method phase = AgentSceneController.class.getMethod(
                "phase", String.class, AgentScenePhaseReportDTO.class);
        assertEquals("/{sceneId}/phases", phase.getAnnotation(PostMapping.class).value()[0]);
    }

    @Test
    void mockMvcWritesStructuredSafeSseBytesWithBlankFraming() throws Exception {
        AgentSceneService service = mock(AgentSceneService.class);
        MockMvc mvc = mvc(service);
        UnsafeEvent event = unsafeStateEvent();
        when(service.events("juyiting-main", 128L)).thenReturn(Flux.just(event));

        MvcResult result = completeAsync(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "128")
                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn(), mvc);

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentType().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.matches("(?s)^id: ?129\\r?\\nevent: ?agent-scene-state-updated\\r?\\n"
                + "data: ?\\{.*}\\r?\\n\\r?\\n$"), body);
        assertEquals(1, body.lines().filter(line -> line.startsWith("data:")).count(), body);
        assertTrue(body.contains("agent-songjiang"), body);
        assertNoSecrets(body);
    }

    @Test
    void mockMvcUsesGreatestValidatedQueryOrLastEventIdCursor() throws Exception {
        AgentSceneService service = mock(AgentSceneService.class);
        MockMvc mvc = mvc(service);
        AgentSceneEventDTO resync = new AgentSceneEventDTO();
        resync.setSceneVersion(512L);
        resync.setEventType("resync-required");
        when(service.events("juyiting-main", 4L)).thenReturn(Flux.just(resync));
        when(service.events("juyiting-main", 7L)).thenReturn(Flux.just(resync));
        when(service.events("juyiting-main", 9L)).thenReturn(Flux.just(resync));
        when(service.events("juyiting-main", 12L)).thenReturn(Flux.just(resync));

        completeAsync(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "4"))
                .andExpect(request().asyncStarted()).andReturn(), mvc);

        MvcResult fromHeader = completeAsync(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .header("Last-Event-ID", "7"))
                .andExpect(request().asyncStarted()).andReturn(), mvc);
        String headerBody = fromHeader.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(headerBody.matches("(?s)^id: ?512\\r?\\nevent: ?resync-required\\r?\\ndata: ?\\{.*}"
                + "\\r?\\n\\r?\\n$"), headerBody);

        completeAsync(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "4")
                .header("Last-Event-ID", "12"))
                .andExpect(request().asyncStarted()).andReturn(), mvc);
        completeAsync(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "9")
                .header("Last-Event-ID", "7"))
                .andExpect(request().asyncStarted()).andReturn(), mvc);
        verify(service).events("juyiting-main", 4L);
        verify(service).events("juyiting-main", 7L);
        verify(service).events("juyiting-main", 9L);
        verify(service).events("juyiting-main", 12L);
    }

    @Test
    void snapshotAndPhaseRemainJsonResultResponsesAndPhaseUsesSafeCopy() throws Exception {
        AgentSceneService service = mock(AgentSceneService.class);
        MockMvc mvc = mvc(service);
        AgentSceneSnapshotDTO snapshot = new AgentSceneSnapshotDTO();
        snapshot.setSceneId("juyiting-main");
        snapshot.setSceneVersion(4L);
        when(service.snapshot("juyiting-main")).thenReturn(snapshot);
        AgentScenePhaseResultDTO phaseResult = new AgentScenePhaseResultDTO();
        phaseResult.setReportId("report-1");
        phaseResult.setStateVersion(3L);
        phaseResult.setResult("accepted");
        when(service.reportPhase(eq("juyiting-main"), any())).thenReturn(phaseResult);

        mvc.perform(get("/agent/scenes/juyiting-main/snapshot"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"sceneVersion\":4")));
        mvc.perform(post("/agent/scenes/juyiting-main/phases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportId":" report-1 ","agentId":" agent-songjiang ",
                                 "stateVersion":3,"phase":"arrived","regionId":" council-table ",
                                 "occurredAt":1234}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"result\":\"accepted\"")));

        ArgumentCaptor<AgentScenePhaseReportDTO> captor =
                ArgumentCaptor.forClass(AgentScenePhaseReportDTO.class);
        verify(service).reportPhase(eq("juyiting-main"), captor.capture());
        assertEquals(AgentScenePhaseReportDTO.class, captor.getValue().getClass());
        assertEquals("report-1", captor.getValue().getReportId());
        assertEquals("agent-songjiang", captor.getValue().getAgentId());
    }

    @Test
    void returnsTruthfulSafeStatusesForBindingBodyValidationAndConflict() throws Exception {
        AgentSceneService service = mock(AgentSceneService.class);
        MockMvc mvc = mvc(service);

        assertSafeError(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "-1")).andReturn(), 400);
        assertSafeError(mvc.perform(get("/agent/scenes/{sceneId}/snapshot", "x".repeat(101)))
                .andReturn(), 400);
        assertSafeError(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "token-secret-not-a-number")).andReturn(), 400);
        assertSafeError(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .header("Last-Event-ID", "token-secret-invalid")).andReturn(), 400);
        assertSafeError(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "7")
                .header("Last-Event-ID", "token-secret-invalid")).andReturn(), 400);
        assertSafeError(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "token-secret-not-a-number")
                .header("Last-Event-ID", "7")).andReturn(), 400);
        assertSafeError(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "7")
                .header("Last-Event-ID", "-2")).andReturn(), 400);
        assertSafeError(mvc.perform(get("/agent/scenes/juyiting-main/events")
                .param("sinceVersion", "-1")
                .header("Last-Event-ID", "7")).andReturn(), 400);
        assertSafeError(mvc.perform(post("/agent/scenes/juyiting-main/phases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"token-secret\",broken}"))
                .andReturn(), 400);

        when(service.snapshot("juyiting-main"))
                .thenThrow(new IllegalArgumentException("token-secret\nprivate stack trace"));
        assertSafeError(mvc.perform(get("/agent/scenes/juyiting-main/snapshot")).andReturn(), 422);

        when(service.reportPhase(eq("juyiting-main"), any()))
                .thenThrow(new AgentBizException("SCENE_CONFLICT", "token-secret conflict detail"));
        assertSafeError(mvc.perform(post("/agent/scenes/juyiting-main/phases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPhaseJson())).andReturn(), 409);

        doThrow(new IllegalStateException("token-secret internal stack detail"))
                .when(service).snapshot("juyiting-main");
        assertSafeError(mvc.perform(get("/agent/scenes/juyiting-main/snapshot")).andReturn(), 500);
    }

    @Test
    void emitterAndMvcCompletionCancelFluxAndUseBoundedTimeout() throws Exception {
        AgentSceneService service = mock(AgentSceneService.class);
        AgentSceneController controller = new AgentSceneController(service);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(service.events("juyiting-main", 0L))
                .thenReturn(Flux.<AgentSceneEventDTO>never().doOnCancel(() -> cancelled.set(true)));

        SseEmitter emitter = controller.events("juyiting-main", null, null);

        assertNotNull(emitter.getTimeout());
        assertTrue(emitter.getTimeout() > 0 && emitter.getTimeout() <= 60_000L);
        emitter.complete();
        assertTrue(cancelled.get());

        AtomicBoolean mvcCancelled = new AtomicBoolean();
        when(service.events("juyiting-main", 7L))
                .thenReturn(Flux.<AgentSceneEventDTO>never().doOnCancel(() -> mvcCancelled.set(true)));
        MvcResult open = mvc(service).perform(get("/agent/scenes/juyiting-main/events")
                        .param("sinceVersion", "7"))
                .andExpect(request().asyncStarted()).andReturn();
        open.getRequest().getAsyncContext().complete();
        assertTrue(mvcCancelled.get());
    }

    @Test
    void unsafeEventFieldInjectionCompletesWithoutWritingInjectedFrame() throws Exception {
        AgentSceneService service = mock(AgentSceneService.class);
        MockMvc mvc = mvc(service);
        AgentSceneEventDTO injected = new AgentSceneEventDTO();
        injected.setSceneVersion(1L);
        injected.setEventType("safe\nid:999\nevent:hacked");
        when(service.events("juyiting-main", 0L)).thenReturn(Flux.just(injected));

        MvcResult initial = mvc.perform(get("/agent/scenes/juyiting-main/events"))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult result = completeAsync(initial, mvc);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("id:999"), body);
        assertFalse(body.contains("event:hacked"), body);
        assertNoSecrets(body);
    }

    @Test
    void asyncServiceErrorsUseSafeStatusWithoutRawDetails() throws Exception {
        AgentSceneService service = mock(AgentSceneService.class);
        MockMvc mvc = mvc(service);
        when(service.events("juyiting-main", 3L)).thenReturn(Flux.error(
                new IllegalArgumentException("token-secret\njava.lang.RuntimeException: private stack")));

        MvcResult initial = mvc.perform(get("/agent/scenes/juyiting-main/events")
                        .param("sinceVersion", "3"))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult result = completeAsync(initial, mvc);

        assertEquals(422, result.getResponse().getStatus());
        assertNoSecrets(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MockMvc mvc(AgentSceneService service) {
        return MockMvcBuilders.standaloneSetup(new AgentSceneController(service)).build();
    }

    private MvcResult completeAsync(MvcResult initial, MockMvc mvc) throws Exception {
        return mvc.perform(asyncDispatch(initial)).andReturn();
    }

    private void assertSafeError(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus());
        assertNoSecrets(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertNoSecrets(String text) {
        for (String forbidden : new String[] {
                "token-secret", "apiKey", "chatText", "private-chat", "rawResponse",
                "raw-model-output", "stackTrace", "stack trace", "java.lang.RuntimeException"}) {
            assertFalse(text.contains(forbidden), forbidden + ": " + text);
        }
    }

    private UnsafeEvent unsafeStateEvent() {
        UnsafeEvent event = new UnsafeEvent();
        event.setSceneVersion(129L);
        event.setEventType("agent-scene-state-updated");
        event.setOccurredAt(1234L);
        UnsafeState state = new UnsafeState();
        state.setAgentId("agent-songjiang");
        state.setPersonaCode("songjiang");
        state.setBehavior("moving_to_bounty");
        state.setTargetRegionId("bounty-board");
        state.setPhase("moving");
        state.setStateVersion(7L);
        state.setStartedAt(1000L);
        event.setState(state);
        return event;
    }

    private String validPhaseJson() {
        return """
                {"reportId":"report-1","agentId":"agent-songjiang","stateVersion":3,
                 "phase":"arrived","regionId":"council-table","occurredAt":1234}
                """;
    }

    private static class UnsafeEvent extends AgentSceneEventDTO {
        public String getApiKey() {
            return "token-secret";
        }

        public String getChatText() {
            return "private-chat";
        }

        public String getRawResponse() {
            return "raw-model-output";
        }
    }

    private static class UnsafeState extends AgentSceneStateDTO {
        public Map<String, Object> getStackTrace() {
            return Map.of("exception", "java.lang.RuntimeException");
        }
    }
}
