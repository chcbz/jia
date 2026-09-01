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
import cn.jia.chat.voice.provider.OpenAiCompatibleSpeechSynthesisProvider;
import cn.jia.chat.voice.state.VoiceBeginResult;
import cn.jia.chat.voice.state.VoiceCachedResult;
import cn.jia.chat.voice.state.VoiceDigests;
import cn.jia.chat.voice.state.VoiceOperation;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceReservation;
import cn.jia.chat.voice.state.VoiceStateUnavailableException;
import cn.jia.chat.voice.validation.VoiceAudioUpload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoiceServicesTest {
    private static final String REQUEST_ID = "01JVOICESERVICE0001";
    private static final VoiceIdentity IDENTITY = new VoiceIdentity("tenant", "client", "subject");

    @Test
    void defaultOffFailsBeforeProviderDispatch() {
        VoiceSpeechProperties properties = properties();
        properties.setEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        SpeechTranscriptionProvider provider = transcriptionProvider(calls, null);
        FakeCoordinator coordinator = new FakeCoordinator();
        SpeechTranscriptionService service = transcriptionService(properties, provider, coordinator);

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.requireAvailable(REQUEST_ID));

        assertEquals(VoiceErrorCode.DISABLED, error.error());
        assertEquals(0, calls.get());
        assertEquals(0, coordinator.beginCalls);
    }

    @Test
    void successfulTranscriptionCachesAndExactReplaySkipsProvider() throws Exception {
        VoiceSpeechProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        SpeechTranscriptionProvider provider = transcriptionProvider(calls,
                new SpeechTranscriptionResult("林冲领命", "zh"));
        FakeCoordinator coordinator = new FakeCoordinator();
        SpeechTranscriptionService service = transcriptionService(properties, provider, coordinator);
        VoiceAudioUpload upload = upload();

        var first = service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", upload);
        assertEquals("林冲领命", first.text());
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", coordinator.terminal);
        assertEquals(1, coordinator.releaseCalls);

        coordinator.next = VoiceBeginResult.replay(coordinator.cached);
        var replay = service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", upload);
        assertEquals("林冲领命", replay.text());
        assertEquals(1, calls.get());
    }

    @Test
    void timeoutIsPersistedUnknownWithoutRetryAndRetrySeesResultUnknown() {
        VoiceSpeechProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        SpeechTranscriptionProvider provider = new SpeechTranscriptionProvider() {
            @Override public String alias() { return "openai-compatible"; }
            @Override public SpeechTranscriptionResult transcribe(
                    cn.jia.chat.voice.SpeechTranscriptionRequest request) throws SpeechProviderException {
                calls.incrementAndGet();
                throw new SpeechProviderException(SpeechProviderException.FailureKind.TIMEOUT, "safe");
            }
        };
        FakeCoordinator coordinator = new FakeCoordinator();
        SpeechTranscriptionService service = transcriptionService(properties, provider, coordinator);

        VoiceException timeout = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", upload()));
        assertEquals(VoiceErrorCode.PROVIDER_TIMEOUT, timeout.error());
        assertEquals("FAILED_UNKNOWN", coordinator.terminal);
        assertEquals(1, calls.get());

        coordinator.next = VoiceBeginResult.outcome(VoiceBeginResult.Outcome.RESULT_UNKNOWN);
        VoiceException unknown = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", upload()));
        assertEquals(VoiceErrorCode.RESULT_UNKNOWN, unknown.error());
        assertEquals(1, calls.get());
    }

    @Test
    void redisOutageBeforeReservationFailsClosedBeforeProvider() {
        VoiceSpeechProperties properties = properties();
        AtomicInteger calls = new AtomicInteger();
        VoiceRequestCoordinator unavailable = new VoiceRequestCoordinator() {
            @Override public VoiceBeginResult begin(VoiceOperation operation, String scope, String id, String digest) {
                throw new VoiceStateUnavailableException();
            }
            @Override public void succeed(VoiceReservation reservation, VoiceCachedResult result) { }
            @Override public void failKnown(VoiceReservation reservation) { }
            @Override public void failUnknown(VoiceReservation reservation) { }
            @Override public void release(VoiceReservation reservation) { }
        };
        SpeechTranscriptionService service = transcriptionService(
                properties, transcriptionProvider(calls, null), unavailable);

        VoiceException error = assertThrows(VoiceException.class,
                () -> service.transcribe(IDENTITY, REQUEST_ID, "zh-CN", upload()));
        assertEquals(VoiceErrorCode.UNAVAILABLE, error.error());
        assertEquals(0, calls.get());
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
        coordinator.next = VoiceBeginResult.replay(coordinator.cached);
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
            VoiceRequestCoordinator coordinator) {
        return new SpeechTranscriptionService(properties, provider, coordinator,
                new VoiceDigests(properties), new ObjectMapper());
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

    private static VoiceAudioUpload upload() {
        return new VoiceAudioUpload(Path.of("fixture.webm"), 128,
                "audio/webm", new byte[32], 1200);
    }

    private static SpeechTranscriptionProvider transcriptionProvider(
            AtomicInteger calls, SpeechTranscriptionResult result) {
        return new SpeechTranscriptionProvider() {
            @Override public String alias() { return "openai-compatible"; }
            @Override public SpeechTranscriptionResult transcribe(cn.jia.chat.voice.SpeechTranscriptionRequest request) {
                calls.incrementAndGet();
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
        private VoiceBeginResult next;
        private VoiceCachedResult cached;
        private int beginCalls;
        private int releaseCalls;
        private String terminal;

        @Override
        public VoiceBeginResult begin(VoiceOperation operation, String scope, String requestId, String digest) {
            beginCalls++;
            if (next != null) return next;
            return VoiceBeginResult.reserved(new VoiceReservation(
                    operation, scope, requestId, digest, "lease"));
        }

        @Override
        public void succeed(VoiceReservation reservation, VoiceCachedResult result) {
            terminal = "SUCCEEDED";
            cached = result;
        }

        @Override public void failKnown(VoiceReservation reservation) { terminal = "FAILED_KNOWN"; }
        @Override public void failUnknown(VoiceReservation reservation) { terminal = "FAILED_UNKNOWN"; }
        @Override public void release(VoiceReservation reservation) { releaseCalls++; }
    }
}
