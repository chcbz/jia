package cn.jia.agent.api;

import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentScenePhaseReportDTO;
import cn.jia.agent.entity.AgentScenePhaseResultDTO;
import cn.jia.agent.entity.AgentSceneSnapshotDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.core.entity.JsonResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSceneControllerTest {

    @Test
    void exposesExactSnapshotEventsAndPhaseRoutes() throws Exception {
        RequestMapping root = AgentSceneController.class.getAnnotation(RequestMapping.class);
        assertEquals("/agent/scenes", root.value()[0]);

        Method snapshot = AgentSceneController.class.getMethod("snapshot", String.class);
        assertEquals("/{sceneId}/snapshot", snapshot.getAnnotation(GetMapping.class).value()[0]);

        Method events = AgentSceneController.class.getMethod("events", String.class, long.class);
        GetMapping eventsMapping = events.getAnnotation(GetMapping.class);
        assertEquals("/{sceneId}/events", eventsMapping.value()[0]);
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, eventsMapping.produces()[0]);
        RequestParam sinceVersion = events.getParameters()[1].getAnnotation(RequestParam.class);
        assertEquals("0", sinceVersion.defaultValue());

        Method phase = AgentSceneController.class.getMethod(
                "phase", String.class, AgentScenePhaseReportDTO.class);
        assertEquals("/{sceneId}/phases", phase.getAnnotation(PostMapping.class).value()[0]);
        assertEquals(1, AgentSceneController.class.getDeclaredFields().length);
        assertEquals(AgentSceneService.class, AgentSceneController.class.getDeclaredFields()[0].getType());
    }

    @Test
    void snapshotAndPhaseUseJsonResultAndDelegateOnlySafeCopies() {
        AgentSceneService service = mock(AgentSceneService.class);
        AgentSceneController controller = new AgentSceneController(service);
        AgentSceneSnapshotDTO snapshot = new AgentSceneSnapshotDTO();
        snapshot.setSceneId("juyiting-main");
        when(service.snapshot("juyiting-main")).thenReturn(snapshot);

        JsonResult<AgentSceneSnapshotDTO> snapshotResult = controller.snapshot(" juyiting-main ");
        assertSame(snapshot, snapshotResult.getData());

        UnsafePhaseReport request = validPhaseReport();
        AgentScenePhaseResultDTO phaseResult = new AgentScenePhaseResultDTO();
        phaseResult.setReportId("report-1");
        when(service.reportPhase(org.mockito.ArgumentMatchers.eq("juyiting-main"), any()))
                .thenReturn(phaseResult);

        JsonResult<AgentScenePhaseResultDTO> result = controller.phase("juyiting-main", request);

        assertSame(phaseResult, result.getData());
        ArgumentCaptor<AgentScenePhaseReportDTO> captor =
                ArgumentCaptor.forClass(AgentScenePhaseReportDTO.class);
        verify(service).reportPhase(org.mockito.ArgumentMatchers.eq("juyiting-main"), captor.capture());
        AgentScenePhaseReportDTO forwarded = captor.getValue();
        assertNotSame(request, forwarded);
        assertEquals(AgentScenePhaseReportDTO.class, forwarded.getClass());
        assertEquals("report-1", forwarded.getReportId());
        assertEquals("agent-songjiang", forwarded.getAgentId());
    }

    @Test
    void formatsOneLineAllowlistedSseWithoutSecretsOrFieldInjection() {
        AgentSceneService service = mock(AgentSceneService.class);
        AgentSceneController controller = new AgentSceneController(service);
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
        when(service.events("juyiting-main", 128L)).thenReturn(Flux.just(event));

        String formatted = controller.events("juyiting-main", 128L).blockFirst();

        assertTrue(formatted.startsWith(
                "id: 129\nevent: agent-scene-state-updated\ndata: {"), formatted);
        assertTrue(formatted.endsWith("\n\n"), formatted);
        assertFalse(formatted.contains("\r"), formatted);
        assertEquals(5, formatted.split("\n", -1).length, formatted);
        assertTrue(formatted.contains("agent-songjiang"), formatted);
        for (String forbidden : new String[] {
                "apiKey", "token-secret", "chatText", "private-chat", "rawResponse",
                "raw-model-output", "stackTrace", "java.lang.RuntimeException"}) {
            assertFalse(formatted.contains(forbidden), forbidden + ": " + formatted);
        }
    }

    @Test
    void formatsResyncRequiredWithCurrentVersionAndNoSyntheticState() {
        AgentSceneService service = mock(AgentSceneService.class);
        AgentSceneController controller = new AgentSceneController(service);
        AgentSceneEventDTO event = new AgentSceneEventDTO();
        event.setSceneVersion(512L);
        event.setEventType("resync-required");
        when(service.events("juyiting-main", 2L)).thenReturn(Flux.just(event));

        String formatted = controller.events("juyiting-main", 2L).blockFirst();

        assertTrue(formatted.startsWith("id: 512\nevent: resync-required\ndata: "), formatted);
        assertFalse(formatted.contains("\"state\":{"), formatted);
        assertTrue(formatted.endsWith("\n\n"), formatted);
    }

    @Test
    void rejectsInvalidSceneVersionBodyAndInjectedEventFieldsFailClosed() {
        AgentSceneService service = mock(AgentSceneService.class);
        AgentSceneController controller = new AgentSceneController(service);

        AgentSceneController.SceneRequestException invalidScene = assertThrows(
                AgentSceneController.SceneRequestException.class, () -> controller.snapshot(" \t "));
        assertThrows(IllegalArgumentException.class, () -> controller.events("juyiting-main", -1L));
        assertThrows(IllegalArgumentException.class, () -> controller.phase("juyiting-main", null));
        assertThrows(IllegalArgumentException.class,
                () -> controller.phase("juyiting-main", new AgentScenePhaseReportDTO()));
        verify(service, never()).snapshot(any());
        verify(service, never()).events(any(), anyLong());
        verify(service, never()).reportPhase(any(), any());

        JsonResult<Void> validationResult = controller.handleSceneRequestException(invalidScene);
        assertEquals(400, validationResult.getStatus());
        assertEquals("sceneId is required", validationResult.getMsg());
        JsonResult<Void> malformedResult = controller.handleUnreadableBody();
        assertEquals(400, malformedResult.getStatus());
        assertEquals("Invalid scene request body", malformedResult.getMsg());

        AgentSceneEventDTO injected = new AgentSceneEventDTO();
        injected.setSceneVersion(1L);
        injected.setEventType("safe\nid: 999\nevent: hacked");
        when(service.events("juyiting-main", 0L)).thenReturn(Flux.just(injected));
        StepVerifier.create(controller.events("juyiting-main", 0L))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void cancellationAndErrorsPropagateWithoutUnsafeSseFallback() {
        AgentSceneService service = mock(AgentSceneService.class);
        AgentSceneController controller = new AgentSceneController(service);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(service.events("juyiting-main", 0L))
                .thenReturn(Flux.<AgentSceneEventDTO>never().doOnCancel(() -> cancelled.set(true)));

        Disposable subscription = controller.events("juyiting-main", 0L).subscribe();
        subscription.dispose();
        assertTrue(cancelled.get());

        IllegalStateException failure = new IllegalStateException(
                "token-secret\njava.lang.RuntimeException: private stack");
        when(service.events("juyiting-main", 7L)).thenReturn(Flux.error(failure));
        StepVerifier.create(controller.events("juyiting-main", 7L))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    private UnsafePhaseReport validPhaseReport() {
        UnsafePhaseReport report = new UnsafePhaseReport();
        report.setReportId(" report-1 ");
        report.setAgentId(" agent-songjiang ");
        report.setStateVersion(7L);
        report.setPhase("arrived");
        report.setRegionId(" council-table ");
        report.setOccurredAt(1234L);
        return report;
    }

    private static class UnsafePhaseReport extends AgentScenePhaseReportDTO {
        public String getToken() {
            return "token-secret";
        }
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
