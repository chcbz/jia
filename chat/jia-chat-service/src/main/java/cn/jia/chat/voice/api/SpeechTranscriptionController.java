package cn.jia.chat.voice.api;

import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.service.SpeechTranscriptionService;
import cn.jia.chat.voice.validation.VoiceAudioUpload;
import cn.jia.chat.voice.validation.VoiceAudioUploadFactory;
import cn.jia.chat.voice.validation.VoiceIdentityResolver;
import cn.jia.chat.voice.validation.VoiceRequestValidator;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/chat/speech")
public class SpeechTranscriptionController {
    private static final Set<String> ALLOWED_PARAMETERS = Set.of(
            "requestId", "language", "durationMs");
    private static final Set<String> ALLOWED_FILES = Set.of("audio");

    private final VoiceIdentityResolver identityResolver;
    private final VoiceRequestValidator validator;
    private final VoiceSpeechProperties properties;
    private final VoiceAudioUploadFactory uploadFactory;
    private final SpeechTranscriptionService service;

    public SpeechTranscriptionController(
            VoiceIdentityResolver identityResolver,
            VoiceRequestValidator validator,
            VoiceSpeechProperties properties,
            VoiceAudioUploadFactory uploadFactory,
            SpeechTranscriptionService service) {
        this.identityResolver = identityResolver;
        this.validator = validator;
        this.properties = properties;
        this.uploadFactory = uploadFactory;
        this.service = service;
    }

    @PostMapping(path = "/transcriptions", consumes = "multipart/form-data", produces = "application/json")
    public ResponseEntity<JsonResult<VoiceTranscriptionResponse>> transcribe(
            HttpServletRequest servletRequest, Authentication authentication) {
        VoiceIdentity identity = identityResolver.resolve(authentication);
        if (!(servletRequest instanceof MultipartHttpServletRequest request)) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, null);
        }
        String requestId = validatedSingleRequestId(request.getParameterMap());
        rejectUnknownOrRepeatedFields(request, requestId);
        String language = validator.language(single(request.getParameterMap(), "language", requestId),
                properties.getTranscription(), requestId);
        validator.clientDuration(single(request.getParameterMap(), "durationMs", requestId), requestId);
        service.requireAvailable(requestId);
        MultipartFile audio = singleAudio(request.getMultiFileMap(), requestId);
        try (VoiceAudioUpload upload = uploadFactory.create(audio, requestId)) {
            VoiceTranscriptionResponse data = service.transcribe(identity, requestId, language, upload);
            return ResponseEntity.ok(JsonResult.success(data));
        }
    }

    private String validatedSingleRequestId(Map<String, String[]> parameters) {
        String[] values = parameters.get("requestId");
        if (values == null || values.length != 1) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, null);
        }
        return validator.requestId(values[0]);
    }

    private static void rejectUnknownOrRepeatedFields(
            MultipartHttpServletRequest request, String requestId) {
        if (!ALLOWED_PARAMETERS.containsAll(request.getParameterMap().keySet())
                || !ALLOWED_FILES.containsAll(request.getMultiFileMap().keySet())) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
        }
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (entry.getValue() == null || entry.getValue().length != 1) {
                throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
            }
        }
        for (Map.Entry<String, java.util.List<MultipartFile>> entry
                : request.getMultiFileMap().entrySet()) {
            if (!"audio".equals(entry.getKey()) || entry.getValue() == null
                    || entry.getValue().size() != 1) {
                throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
            }
        }
    }

    private static String single(Map<String, String[]> parameters, String name, String requestId) {
        String[] values = parameters.get(name);
        if (values == null) {
            return null;
        }
        if (values.length != 1) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
        }
        return values[0];
    }

    private static MultipartFile singleAudio(
            MultiValueMap<String, MultipartFile> files, String requestId) {
        java.util.List<MultipartFile> values = files.get("audio");
        if (values == null || values.size() != 1 || files.size() != 1) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
        }
        return values.get(0);
    }
}
