package cn.jia.chat.voice.service;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechSynthesisProvider;
import cn.jia.chat.voice.SpeechSynthesisResult;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.SpeechTranscriptionResult;
import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.api.VoiceSynthesisRequest;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.provider.FileChannelSpeechTranscriptionProvider;
import cn.jia.chat.voice.provider.FileChannelSpeechTranscriptionRequest;
import cn.jia.chat.voice.provider.OpenAiCompatibleSpeechSynthesisProvider;
import cn.jia.chat.voice.state.VoiceAdmission;
import cn.jia.chat.voice.state.VoiceAdmissionResult;
import cn.jia.chat.voice.state.VoiceBeginResult;
import cn.jia.chat.voice.state.VoiceCachedResult;
import cn.jia.chat.voice.state.VoiceDigests;
import cn.jia.chat.voice.state.VoiceOperation;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceReservation;
import cn.jia.chat.voice.state.VoiceStateUnavailableException;
import cn.jia.chat.voice.validation.VoiceAudioUpload;
import cn.jia.chat.voice.validation.VoiceAudioUploadFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VoiceServicesTest {
    private static final String REQUEST_ID = "01JVOICESERVICE0001";
    private static final VoiceIdentity IDENTITY = new VoiceIdentity("tenant", "client", "subject");
    private static final MultipartFile MULTIPART = new MockMultipartFile(
            "audio", "private.webm", "audio/webm;codecs=opus", new byte[]{1});

    @Test
    void defaultOffFailsBeforeProviderDispatchOrAdmission() {
        VoiceSpeechProperties properties = properties();
        properties.setEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        FakeCoordinator coordinator = new FakeCoordinator();
        VoiceAudioUploadFactory factory = mock(VoiceAudioUploadFactory.class);
        SpeechTranscriptionService service = transcriptionService(
                properties, transcriptionProvider(calls, null, null), coordinator, factory);

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.requireAvailable(REQUEST_ID));

        assertEquals(VoiceErrorCode.DISABLED, error.error());
        assertEquals(0, calls.get());
        assertEquals(0, coordinator.admitCalls);
        verifyNoInteractions(factory);
    }

    @Test
    void successfulTranscriptionCachesAndReplaySkipsProviderButBothReleaseAdmission() {
        VoiceSpeechProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        FakeCoordinator coordinator = new FakeCoordinator();
        VoiceAudioUploadFactory factory = factoryReturningUploads();
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls, new SpeechTranscriptionResult("林冲领命", "zh"), null),
                coordinator, factory);

        var first = service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART);
        assertEquals("林冲领命", first.text());
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", coordinator.terminal);
        assertEquals(1, coordinator.admissionReleaseCalls);

        coordinator.nextAdmitted = VoiceBeginResult.replay(coordinator.cached);
        var replay = service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART);
        assertEquals("林冲领命", replay.text());
        assertEquals(1, calls.get());
        assertEquals(2, coordinator.admissionReleaseCalls);
        assertEquals(2, coordinator.admitCalls);
        assertEquals(2, coordinator.admittedBeginCalls);
    }

    @Test
    void quotaAndGlobalRejectionsDoNotTouchMultipartFactoryOrPayload() {
        for (VoiceAdmissionResult.Outcome outcome : new VoiceAdmissionResult.Outcome[]{
                VoiceAdmissionResult.Outcome.RATE_LIMITED,
                VoiceAdmissionResult.Outcome.CONCURRENCY_LIMITED}) {
            VoiceSpeechProperties properties = properties();
            FakeCoordinator coordinator = new FakeCoordinator();
            coordinator.admissionOutcome = outcome;
            VoiceAudioUploadFactory factory = mock(VoiceAudioUploadFactory.class);
            MultipartFile multipart = mock(MultipartFile.class);
            AtomicInteger providerCalls = new AtomicInteger();
            SpeechTranscriptionService service = transcriptionService(properties,
                    transcriptionProvider(providerCalls,
                            new SpeechTranscriptionResult("unexpected", "zh"), null),
                    coordinator, factory);

            VoiceException error = assertThrows(VoiceException.class,
                    () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", multipart));

            assertEquals(outcome == VoiceAdmissionResult.Outcome.RATE_LIMITED
                    ? VoiceErrorCode.RATE_LIMITED : VoiceErrorCode.IN_PROGRESS, error.error());
            assertEquals(1, coordinator.admitCalls);
            assertEquals(0, coordinator.admittedBeginCalls);
            assertEquals(0, coordinator.admissionReleaseCalls);
            assertEquals(0, providerCalls.get());
            verifyNoInteractions(factory, multipart);
        }
    }

    @Test
    void malformedOrCrossIdentityAdmissionFailsBeforeMultipartMaterialization() {
        VoiceSpeechProperties properties = properties();
        String expectedScope = new VoiceDigests(properties).identityScope(IDENTITY);
        for (VoiceAdmission admission : new VoiceAdmission[]{
                null,
                new VoiceAdmission(VoiceOperation.SYNTHESIS,
                        expectedScope, REQUEST_ID, "lease"),
                new VoiceAdmission(VoiceOperation.TRANSCRIPTION,
                        "wrong-scope", REQUEST_ID, "lease"),
                new VoiceAdmission(VoiceOperation.TRANSCRIPTION,
                        expectedScope, "wrong-request", "lease"),
                new VoiceAdmission(VoiceOperation.TRANSCRIPTION,
                        expectedScope, REQUEST_ID, " ")}) {
            FakeCoordinator coordinator = new FakeCoordinator();
            coordinator.admissionOverride = VoiceAdmissionResult.admitted(admission);
            VoiceAudioUploadFactory factory = mock(VoiceAudioUploadFactory.class);
            MultipartFile multipart = mock(MultipartFile.class);
            AtomicInteger providerCalls = new AtomicInteger();
            SpeechTranscriptionService service = transcriptionService(properties,
                    transcriptionProvider(providerCalls, null, null), coordinator, factory);

            VoiceException error = assertThrows(VoiceException.class,
                    () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", multipart));

            assertEquals(VoiceErrorCode.UNAVAILABLE, error.error());
            assertEquals(1, coordinator.admitCalls);
            assertEquals(0, coordinator.admittedBeginCalls);
            boolean addressable = admission != null && admission.operation() != null
                    && admission.identityScope() != null && !admission.identityScope().isBlank()
                    && admission.requestId() != null && !admission.requestId().isBlank()
                    && admission.leaseToken() != null && !admission.leaseToken().isBlank();
            assertEquals(addressable ? 1 : 0, coordinator.admissionReleaseCalls);
            assertEquals(0, providerCalls.get());
            verifyNoInteractions(factory, multipart);
        }
    }

    @Test
    void uploadMaterializationFailureAfterAdmissionReleasesExactlyOnce() {
        VoiceSpeechProperties properties = properties();
        FakeCoordinator coordinator = new FakeCoordinator();
        VoiceAudioUploadFactory factory = mock(VoiceAudioUploadFactory.class);
        when(factory.create(any(), eq(REQUEST_ID)))
                .thenThrow(VoiceException.of(VoiceErrorCode.INVALID_AUDIO, REQUEST_ID));
        AtomicInteger calls = new AtomicInteger();
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls, null, null), coordinator, factory);

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));

        assertEquals(VoiceErrorCode.INVALID_AUDIO, error.error());
        assertEquals(1, coordinator.admitCalls);
        assertEquals(0, coordinator.admittedBeginCalls);
        assertEquals(1, coordinator.admissionReleaseCalls);
        assertEquals(0, calls.get());
    }

    @Test
    void admittedBeginStateFailureReleasesExactlyOnceWithoutProviderDispatch() {
        VoiceSpeechProperties properties = properties();
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.failAdmittedBegin = true;
        AtomicInteger calls = new AtomicInteger();
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls, null, null), coordinator, factoryReturningUploads());

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));

        assertEquals(VoiceErrorCode.UNAVAILABLE, error.error());
        assertEquals(1, coordinator.admitCalls);
        assertEquals(1, coordinator.admittedBeginCalls);
        assertEquals(1, coordinator.admissionReleaseCalls);
        assertEquals(0, coordinator.reservationReleaseCalls);
        assertEquals(0, calls.get());
    }

    @Test
    void beginRejectionAfterDigestReleasesExactlyOnceWithoutProviderDispatch() {
        VoiceSpeechProperties properties = properties();
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.nextAdmitted = VoiceBeginResult.outcome(
                VoiceBeginResult.Outcome.IDEMPOTENCY_CONFLICT);
        AtomicInteger calls = new AtomicInteger();
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls, null, null), coordinator, factoryReturningUploads());

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));

        assertEquals(VoiceErrorCode.IDEMPOTENCY_CONFLICT, error.error());
        assertEquals(1, coordinator.admissionReleaseCalls);
        assertEquals(0, coordinator.reservationReleaseCalls);
        assertEquals(0, calls.get());
    }

    @Test
    void malformedReservationFailsBeforeProviderAndReleasesAdmissionExactlyOnce() {
        VoiceSpeechProperties properties = properties();
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.returnMismatchedReservation = true;
        AtomicInteger calls = new AtomicInteger();
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls, null, null), coordinator, factoryReturningUploads());

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));

        assertEquals(VoiceErrorCode.UNAVAILABLE, error.error());
        assertEquals(1, coordinator.admissionReleaseCalls);
        assertEquals(0, coordinator.reservationReleaseCalls);
        assertEquals(0, calls.get());
    }

    @Test
    void serializedSttClientJsonAbove256KiBFailsClosedAndReleasesExactlyOnce() {
        VoiceSpeechProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        FakeCoordinator coordinator = new FakeCoordinator();
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls,
                        new SpeechTranscriptionResult("\"".repeat(140_000), "zh"), null),
                coordinator, factoryReturningUploads());

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));

        assertEquals(VoiceErrorCode.PROVIDER_ERROR, error.error());
        assertEquals("FAILED_KNOWN", coordinator.terminal);
        assertEquals(null, coordinator.cached);
        assertEquals(1, calls.get());
        assertEquals(1, coordinator.admissionReleaseCalls);
    }

    @Test
    void oversizedSttReplayFailsClosedAndReleasesBeforeProviderDispatch() {
        VoiceSpeechProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.nextAdmitted = VoiceBeginResult.replay(new VoiceCachedResult(
                new byte[SpeechTranscriptionService.MAX_CLIENT_JSON_BYTES + 1],
                "application/json"));
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls, new SpeechTranscriptionResult("unexpected", "zh"), null),
                coordinator, factoryReturningUploads());

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));

        assertEquals(VoiceErrorCode.UNAVAILABLE, error.error());
        assertEquals(0, calls.get());
        assertEquals(1, coordinator.admissionReleaseCalls);
    }

    @Test
    void successTerminalWriteFailureFallsBackUnknownAndReleasesExactlyOnce() {
        VoiceSpeechProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.failSucceed = true;
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls, new SpeechTranscriptionResult("林冲领命", "zh"), null),
                coordinator, factoryReturningUploads());

        VoiceException first = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));
        assertEquals(VoiceErrorCode.RESULT_UNKNOWN, first.error());
        assertEquals("FAILED_UNKNOWN", coordinator.terminal);
        assertEquals(1, coordinator.admissionReleaseCalls);
        assertEquals(1, calls.get());
    }

    @Test
    void timeoutIsPersistedUnknownWithoutRetryAndReleasesExactlyOnce() {
        VoiceSpeechProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        FakeCoordinator coordinator = new FakeCoordinator();
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls, null, new SpeechProviderException(
                        SpeechProviderException.FailureKind.TIMEOUT, "safe")),
                coordinator, factoryReturningUploads());

        VoiceException timeout = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));
        assertEquals(VoiceErrorCode.PROVIDER_TIMEOUT, timeout.error());
        assertEquals("FAILED_UNKNOWN", coordinator.terminal);
        assertEquals(1, calls.get());
        assertEquals(1, coordinator.admissionReleaseCalls);
    }

    @Test
    void redisOutageBeforeAdmissionFailsClosedBeforeUploadOrProvider() {
        VoiceSpeechProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        VoiceRequestCoordinator unavailable = new cn.jia.chat.voice.state.UnavailableVoiceRequestCoordinator();
        VoiceAudioUploadFactory factory = mock(VoiceAudioUploadFactory.class);
        SpeechTranscriptionService service = transcriptionService(properties,
                transcriptionProvider(calls, null, null), unavailable, factory);

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));
        assertEquals(VoiceErrorCode.UNAVAILABLE, error.error());
        assertEquals(0, calls.get());
        verifyNoInteractions(factory);
    }

    @Test
    void pathOnlyProviderFailsBeforeAdmissionAndUploadRatherThanReopeningAPath() {
        VoiceSpeechProperties properties = properties();
        SpeechTranscriptionProvider pathOnly = new SpeechTranscriptionProvider() {
            @Override public String alias() { return "openai-compatible"; }
            @Override public SpeechTranscriptionResult transcribe(
                    cn.jia.chat.voice.SpeechTranscriptionRequest request) {
                throw new AssertionError("legacy path provider must not be called");
            }
        };
        FakeCoordinator coordinator = new FakeCoordinator();
        VoiceAudioUploadFactory factory = mock(VoiceAudioUploadFactory.class);
        SpeechTranscriptionService service = transcriptionService(
                properties, pathOnly, coordinator, factory);

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", MULTIPART));

        assertEquals(VoiceErrorCode.UNAVAILABLE, error.error());
        assertEquals(0, coordinator.admitCalls);
        verifyNoInteractions(factory);
    }

    @Test
    void synthesisUnavailableEchoesValidatedRequestIdForMissingHmacOrUnknownProvider() {
        VoiceSpeechProperties missingHmac = properties();
        missingHmac.setIdentityHmacSecret(null);
        SpeechSynthesisService noHmac = synthesisService(missingHmac,
                synthesisProvider(new AtomicInteger(), new byte[]{1}), new FakeCoordinator());
        VoiceException unavailable = assertThrows(VoiceException.class,
                () -> noHmac.requireAvailable(REQUEST_ID));
        assertEquals(VoiceErrorCode.UNAVAILABLE, unavailable.error());
        assertEquals(REQUEST_ID, unavailable.requestId());

        VoiceSpeechProperties unknownProvider = properties();
        unknownProvider.getSynthesis().setProvider("unknown-provider");
        SpeechSynthesisProvider disabled = new cn.jia.chat.voice.provider.DisabledSpeechSynthesisProvider();
        VoiceException unknown = assertThrows(VoiceException.class,
                () -> synthesisService(unknownProvider, disabled, new FakeCoordinator())
                        .requireAvailable(REQUEST_ID));
        assertEquals(VoiceErrorCode.UNAVAILABLE, unknown.error());
        assertEquals(REQUEST_ID, unknown.requestId());
    }

    @Test
    void synthesisReturnsAndReplaysBoundedAudioButRejectsOversizeBody() {
        VoiceSpeechProperties properties = properties();
        FakeCoordinator coordinator = new FakeCoordinator();
        AtomicInteger calls = new AtomicInteger();
        SpeechSynthesisProvider provider = synthesisProvider(calls, new byte[]{1, 2, 3});
        SpeechSynthesisService service = synthesisService(properties, provider, coordinator);
        VoiceSynthesisRequest request = new VoiceSynthesisRequest(
                REQUEST_ID, "林冲领命。", "juyiting-default", "mp3");

        SpeechSynthesisResult first = service.synthesize(IDENTITY, request);
        assertArrayEquals(new byte[]{1, 2, 3}, first.audio());
        assertEquals("SUCCEEDED", coordinator.terminal);
        coordinator.nextLegacy = VoiceBeginResult.replay(coordinator.cached);
        assertArrayEquals(new byte[]{1, 2, 3}, service.synthesize(IDENTITY, request).audio());
        assertEquals(1, calls.get());

        FakeCoordinator tooLargeCoordinator = new FakeCoordinator();
        SpeechSynthesisService tooLarge = synthesisService(properties,
                synthesisProvider(new AtomicInteger(), new byte[
                        OpenAiCompatibleSpeechSynthesisProvider.MAX_AUDIO_BYTES + 1]),
                tooLargeCoordinator);
        VoiceException error = assertThrows(VoiceException.class,
                () -> tooLarge.synthesize(IDENTITY, request));
        assertEquals(VoiceErrorCode.PROVIDER_ERROR, error.error());
        assertEquals("FAILED_KNOWN", tooLargeCoordinator.terminal);
    }

    private SpeechTranscriptionService transcriptionService(
            VoiceSpeechProperties properties, SpeechTranscriptionProvider provider,
            VoiceRequestCoordinator coordinator, VoiceAudioUploadFactory factory) {
        return new SpeechTranscriptionService(properties, provider, coordinator,
                new VoiceDigests(properties), new ObjectMapper(), factory);
    }

    private SpeechSynthesisService synthesisService(
            VoiceSpeechProperties properties, SpeechSynthesisProvider provider,
            VoiceRequestCoordinator coordinator) {
        return new SpeechSynthesisService(properties, provider, coordinator,
                new VoiceDigests(properties));
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

    private static VoiceAudioUploadFactory factoryReturningUploads() {
        VoiceAudioUploadFactory factory = mock(VoiceAudioUploadFactory.class);
        when(factory.create(any(), eq(REQUEST_ID))).thenAnswer(invocation -> upload());
        return factory;
    }

    private static VoiceAudioUpload upload() throws Exception {
        FileChannel channel = mock(FileChannel.class);
        return new VoiceAudioUpload(channel, 128,
                "audio/webm;codecs=opus", new byte[32], 1200);
    }

    private static FileChannelSpeechTranscriptionProvider transcriptionProvider(
            AtomicInteger calls,
            SpeechTranscriptionResult result,
            SpeechProviderException failure) {
        return new FileChannelSpeechTranscriptionProvider() {
            @Override public String alias() { return "openai-compatible"; }

            @Override
            public SpeechTranscriptionResult transcribe(
                    FileChannelSpeechTranscriptionRequest request) throws SpeechProviderException {
                calls.incrementAndGet();
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
        };
    }

    private static SpeechSynthesisProvider synthesisProvider(AtomicInteger calls, byte[] audio) {
        return new SpeechSynthesisProvider() {
            @Override public String alias() { return "openai-compatible"; }
            @Override public SpeechSynthesisResult synthesize(cn.jia.chat.voice.SpeechSynthesisRequest request) {
                calls.incrementAndGet();
                return new SpeechSynthesisResult(audio, "audio/mpeg");
            }
        };
    }

    private static final class FakeCoordinator implements VoiceRequestCoordinator {
        private VoiceAdmissionResult.Outcome admissionOutcome =
                VoiceAdmissionResult.Outcome.ADMITTED;
        private VoiceAdmissionResult admissionOverride;
        private VoiceBeginResult nextAdmitted;
        private VoiceBeginResult nextLegacy;
        private VoiceCachedResult cached;
        private int admitCalls;
        private int admittedBeginCalls;
        private int legacyBeginCalls;
        private int admissionReleaseCalls;
        private int reservationReleaseCalls;
        private String terminal;
        private boolean failSucceed;
        private boolean failAdmittedBegin;
        private boolean returnMismatchedReservation;

        @Override
        public VoiceAdmissionResult admit(
                VoiceOperation operation, String scope, String requestId) {
            admitCalls++;
            if (admissionOverride != null) {
                return admissionOverride;
            }
            if (admissionOutcome != VoiceAdmissionResult.Outcome.ADMITTED) {
                return VoiceAdmissionResult.outcome(admissionOutcome);
            }
            return VoiceAdmissionResult.admitted(new VoiceAdmission(
                    operation, scope, requestId, "admission-" + admitCalls));
        }

        @Override
        public VoiceBeginResult begin(VoiceAdmission admission, String digest) {
            admittedBeginCalls++;
            if (failAdmittedBegin) {
                throw new VoiceStateUnavailableException();
            }
            if (nextAdmitted != null) {
                return nextAdmitted;
            }
            return VoiceBeginResult.reserved(new VoiceReservation(
                    admission.operation(), admission.identityScope(), admission.requestId(),
                    digest, returnMismatchedReservation
                            ? "mismatched-lease" : admission.leaseToken()));
        }

        @Override
        public VoiceBeginResult begin(
                VoiceOperation operation, String scope, String requestId, String digest) {
            legacyBeginCalls++;
            if (nextLegacy != null) {
                return nextLegacy;
            }
            return VoiceBeginResult.reserved(new VoiceReservation(
                    operation, scope, requestId, digest, "legacy-" + legacyBeginCalls));
        }

        @Override
        public void succeed(VoiceReservation reservation, VoiceCachedResult result) {
            if (failSucceed) {
                throw new VoiceStateUnavailableException();
            }
            terminal = "SUCCEEDED";
            cached = result;
        }

        @Override public void failKnown(VoiceReservation reservation) { terminal = "FAILED_KNOWN"; }
        @Override public void failUnknown(VoiceReservation reservation) { terminal = "FAILED_UNKNOWN"; }
        @Override public void release(VoiceAdmission admission) { admissionReleaseCalls++; }
        @Override public void release(VoiceReservation reservation) { reservationReleaseCalls++; }
    }
}
