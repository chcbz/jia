package cn.jia.chat.voice.api;

import cn.jia.chat.voice.SpeechSynthesisResult;
import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.service.SpeechSynthesisService;
import cn.jia.chat.voice.service.SpeechTranscriptionService;
import cn.jia.chat.voice.validation.AudioDurationInspector;
import cn.jia.chat.voice.validation.VoiceAudioUploadFactory;
import cn.jia.chat.voice.validation.VoiceIdentityResolver;
import cn.jia.chat.voice.validation.VoiceRequestValidator;
import cn.jia.core.entity.JsonResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VoiceControllersTest {
    private static final String REQUEST_ID = "01JVOICECONTROL0001";

    @Test
    void transcriptionUsesOnlyJwtIdentityAndExactMultipartAllowlist() throws Exception {
        VoiceSpeechProperties properties = properties();
        SpeechTranscriptionService service = mock(SpeechTranscriptionService.class);
        when(service.transcribe(any(), eq(REQUEST_ID), eq("zh-CN"), any()))
                .thenReturn(new VoiceTranscriptionResponse(REQUEST_ID, "请林教头查看榜文", "zh", 1200));
        SpeechTranscriptionController controller = new SpeechTranscriptionController(
                new VoiceIdentityResolver(), new VoiceRequestValidator(), properties, service);
        MockMultipartHttpServletRequest request = validMultipart(
                "mediarecorder-valid.webm", "audio/webm;codecs=opus");

        ResponseEntity<JsonResult<VoiceTranscriptionResponse>> response = controller.transcribe(
                request, jwt(" exact-tenant ", "client", "subject"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("E0", response.getBody().getCode());
        ArgumentCaptor<VoiceIdentity> identity = ArgumentCaptor.forClass(VoiceIdentity.class);
        verify(service).transcribe(identity.capture(), eq(REQUEST_ID), eq("zh-CN"), any());
        assertEquals(" exact-tenant ", identity.getValue().jiacn());
    }

    @Test
    void transcriptionUploadFactoryRejectsNonUniqueMimeProfilesBeforeReadingContent()
            throws Exception {
        VoiceAudioUploadFactory factory =
                new VoiceAudioUploadFactory(new AudioDurationInspector());

        for (String mediaType : List.of(
                "audio/webm",
                "audio/webm;codecs=vorbis",
                "audio/webm;codecs=opus;codecs=opus",
                "audio/webm;codecs=opus;channels=1",
                "audio/mp4;codecs=mp4a.40.2",
                "audio/ogg;codecs=opus")) {
            MockMultipartHttpServletRequest request = validMultipart(
                    "mediarecorder-valid.webm", mediaType);
            VoiceException error = assertThrows(VoiceException.class,
                    () -> factory.create(request.getFile("audio"), REQUEST_ID), mediaType);
            assertEquals(VoiceErrorCode.UNSUPPORTED_MEDIA, error.error(), mediaType);
            assertEquals(REQUEST_ID, error.requestId(), mediaType);
        }
    }

    @Test
    void transcriptionRejectsUnknownMultipartFieldsBeforeUploadOrProvider() throws Exception {
        VoiceSpeechProperties properties = properties();
        SpeechTranscriptionService service = mock(SpeechTranscriptionService.class);
        SpeechTranscriptionController controller = new SpeechTranscriptionController(
                new VoiceIdentityResolver(), new VoiceRequestValidator(), properties, service);
        MockMultipartHttpServletRequest request = validMultipart(
                "mediarecorder-valid.webm", "audio/webm;codecs=opus");
        request.addParameter("provider", "forged");

        VoiceException error = assertThrows(VoiceException.class,
                () -> controller.transcribe(request, jwt("tenant", "client", "subject")));
        assertEquals(VoiceErrorCode.INVALID_REQUEST, error.error());
        assertEquals(REQUEST_ID, error.requestId());
        verify(service, never()).transcribe(any(), any(), any(), any());
    }


    @Test
    void transcriptionRejectsAudioAsARegularTextParameter() throws Exception {
        VoiceSpeechProperties properties = properties();
        SpeechTranscriptionService service = mock(SpeechTranscriptionService.class);
        SpeechTranscriptionController controller = new SpeechTranscriptionController(
                new VoiceIdentityResolver(), new VoiceRequestValidator(), properties, service);
        MockMultipartHttpServletRequest request = validMultipart(
                "mediarecorder-valid.webm", "audio/webm;codecs=opus");
        request.addParameter("audio", "not-a-file");

        VoiceException error = assertThrows(VoiceException.class,
                () -> controller.transcribe(request, jwt("tenant", "client", "subject")));
        assertEquals(VoiceErrorCode.INVALID_REQUEST, error.error());
        assertEquals(REQUEST_ID, error.requestId());
        verify(service, never()).transcribe(any(), any(), any(), any());
    }

    @Test
    void uploadFactoryEnforcesActualFiveMiBLimitBeforeContainerParsing() {
        byte[] tooLarge = new byte[(int) VoiceAudioUploadFactory.MAX_AUDIO_BYTES + 1];
        Arrays.fill(tooLarge, (byte) 1);
        VoiceAudioUploadFactory factory = new VoiceAudioUploadFactory(new AudioDurationInspector());

        VoiceException error = assertThrows(VoiceException.class,
                () -> factory.create(new MockMultipartFile(
                        "audio", "private-name.webm", "audio/webm;codecs=opus", tooLarge),
                        REQUEST_ID));
        assertEquals(VoiceErrorCode.TOO_LARGE, error.error());
    }

    @Test
    void synthesisRejectsLoneHighSurrogateBeforeValidationAndDigest() {
        VoiceRequestValidator validator = new VoiceRequestValidator();
        VoiceException error = assertThrows(VoiceException.class,
                () -> validator.synthesis(new VoiceSynthesisRequest(
                                REQUEST_ID, "林冲\uD83D", "juyiting-default", "mp3"),
                        properties().getSynthesis()));

        assertEquals(VoiceErrorCode.INVALID_REQUEST, error.error());
        assertEquals(REQUEST_ID, error.requestId());
        assertSynthesisDigestRejects("林冲\uD83D");
    }

    @Test
    void synthesisRejectsLoneLowSurrogateBeforeValidationAndDigest() {
        VoiceRequestValidator validator = new VoiceRequestValidator();
        VoiceException error = assertThrows(VoiceException.class,
                () -> validator.synthesis(new VoiceSynthesisRequest(
                                REQUEST_ID, "林冲\uDE00", "juyiting-default", "mp3"),
                        properties().getSynthesis()));

        assertEquals(VoiceErrorCode.INVALID_REQUEST, error.error());
        assertEquals(REQUEST_ID, error.requestId());
        assertSynthesisDigestRejects("林冲\uDE00");
    }

    @Test
    void synthesisAcceptsValidSurrogatePairAndDigestKeepsExactUtf8() {
        VoiceRequestValidator validator = new VoiceRequestValidator();
        VoiceSynthesisRequest request = new VoiceSynthesisRequest(
                REQUEST_ID, "林冲\uD83D\uDE00", "juyiting-default", "mp3");

        assertEquals(request, validator.synthesis(request, properties().getSynthesis()));
        VoiceSpeechProperties digestProperties = properties();
        digestProperties.setIdentityHmacSecret("01234567890123456789012345678901");
        String paired = new cn.jia.chat.voice.state.VoiceDigests(digestProperties)
                .synthesis(request.text(), request.voice(), request.format());
        String replaced = new cn.jia.chat.voice.state.VoiceDigests(digestProperties)
                .synthesis("林冲?", request.voice(), request.format());
        assertFalse(paired.equals(replaced));
    }

    @Test
    void synthesisReturnsBoundedNoStoreAudioWithExactHeaders() {
        VoiceSpeechProperties properties = properties();
        SpeechSynthesisService service = mock(SpeechSynthesisService.class);
        when(service.synthesize(any(), any())).thenReturn(
                new SpeechSynthesisResult(new byte[]{4, 5, 6}, "audio/mpeg"));
        SpeechSynthesisController controller = new SpeechSynthesisController(
                new VoiceIdentityResolver(), new VoiceRequestValidator(), properties, service);

        ResponseEntity<byte[]> response = controller.synthesize(new VoiceSynthesisRequest(
                REQUEST_ID, "林冲领命。", "juyiting-default", "mp3"),
                jwt("tenant", "client", "subject"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("audio/mpeg", response.getHeaders().getContentType().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(REQUEST_ID, response.getHeaders().getFirst("X-Voice-Request-Id"));
        assertEquals(3, response.getHeaders().getContentLength());
        assertArrayEquals(new byte[]{4, 5, 6}, response.getBody());
    }

    @Test
    void strictSynthesisJsonRejectsProviderModelUrlAndPromptFields() {
        ObjectMapper mapper = new ObjectMapper();
        for (String field : List.of("provider", "model", "url", "prompt", "conversationId")) {
            String json = "{\"requestId\":\"" + REQUEST_ID
                    + "\",\"text\":\"ok\",\"voice\":\"juyiting-default\",\"format\":\"mp3\",\""
                    + field + "\":\"forged\"}";
            assertThrows(JacksonException.class,
                    () -> mapper.readValue(json, VoiceSynthesisRequest.class));
        }
    }

    @Test
    void errorHandlerAlwaysMatchesHttpAndJsonStatusWithSanitizedEcho() {
        VoiceExceptionHandler handler = new VoiceExceptionHandler();
        ResponseEntity<JsonResult<VoiceErrorData>> response = handler.voice(
                VoiceException.of(VoiceErrorCode.TOO_LONG, REQUEST_ID));
        assertEquals(422, response.getStatusCode().value());
        assertEquals(422, response.getBody().getStatus());
        assertEquals("VOICE_TOO_LONG", response.getBody().getCode());
        assertEquals(REQUEST_ID, response.getBody().getData().requestId());

        ResponseEntity<JsonResult<VoiceErrorData>> malformed = handler.malformedMultipart();
        assertEquals(400, malformed.getStatusCode().value());
        assertEquals(400, malformed.getBody().getStatus());
        assertEquals(null, malformed.getBody().getData().requestId());
    }


    @Test
    void realHttpJsonErrorsKeepStatusAndSanitizedRequestId() throws Exception {
        VoiceSpeechProperties properties = properties();
        SpeechSynthesisService service = mock(SpeechSynthesisService.class);
        SpeechSynthesisController controller = new SpeechSynthesisController(
                new VoiceIdentityResolver(), new VoiceRequestValidator(), properties, service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new VoiceExceptionHandler())
                .build();

        mvc.perform(post("/chat/speech/synthesis")
                        .principal(jwt("tenant", "client", "subject"))
                        .contentType("application/json")
                        .content("{\"requestId\":\"" + REQUEST_ID
                                + "\",\"text\":\"ok\","
                                + "\"voice\":\"juyiting-default\","
                                + "\"format\":\"mp3\",\"provider\":\"forged\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VOICE_INVALID_REQUEST"))
                .andExpect(jsonPath("$.data.requestId").value(REQUEST_ID));

        mvc.perform(post("/chat/speech/synthesis")
                        .contentType("application/json")
                        .content("{\"requestId\":\"" + REQUEST_ID
                                + "\",\"text\":\"ok\","
                                + "\"voice\":\"juyiting-default\","
                                + "\"format\":\"mp3\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("VOICE_UNAUTHORIZED"))
                .andExpect(jsonPath("$.data.requestId").doesNotExist());
        verify(service, never()).synthesize(any(), any());
    }

    @Test
    void endpointDependencyGraphContainsNoConversationMessageAgentOrTaskService() {
        for (Class<?> controller : List.of(
                SpeechTranscriptionController.class, SpeechSynthesisController.class)) {
            for (var field : controller.getDeclaredFields()) {
                String type = field.getType().getName().toLowerCase();
                assertFalse(type.contains("conversation") || type.contains("agentservice")
                        || type.contains("taskservice") || type.contains("messagedao"), type);
            }
        }
    }

    private static void assertSynthesisDigestRejects(String text) {
        VoiceException error = assertThrows(VoiceException.class,
                () -> new cn.jia.chat.voice.state.VoiceDigests(properties())
                        .synthesis(text, "juyiting-default", "mp3"));
        assertEquals(VoiceErrorCode.INVALID_REQUEST, error.error());
    }

    private MockMultipartHttpServletRequest validMultipart(String fixture, String mediaType)
            throws Exception {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/chat/speech/transcriptions");
        request.addParameter("requestId", REQUEST_ID);
        request.addParameter("language", "zh-CN");
        request.addParameter("durationMs", "1200");
        try (InputStream input = getClass().getResourceAsStream(
                "/cn/jia/chat/voice/media/" + fixture)) {
            request.addFile(new MockMultipartFile("audio", "private-name", mediaType, input));
        }
        return request;
    }

    private static VoiceSpeechProperties properties() {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setEnabled(true);
        properties.setIdentityHmacSecret("01234567890123456789012345678901");
        properties.getTranscription().setEnabled(true);
        properties.getTranscription().setProvider("openai-compatible");
        properties.getSynthesis().setEnabled(true);
        properties.getSynthesis().setProvider("openai-compatible");
        return properties;
    }

    private static JwtAuthenticationToken jwt(String tenant, String client, String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", tenant)
                .claim("client_id", client)
                .claim("sub", subject)
                .issuedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-01T01:00:00Z"))
                .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
