package cn.jia.chat.voice.api;

import cn.jia.chat.voice.SpeechSynthesisResult;
import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.service.SpeechSynthesisService;
import cn.jia.chat.voice.validation.VoiceIdentityResolver;
import cn.jia.chat.voice.validation.VoiceRequestValidator;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/speech")
public class SpeechSynthesisController {
    private final VoiceIdentityResolver identityResolver;
    private final VoiceRequestValidator validator;
    private final cn.jia.chat.voice.config.VoiceSpeechProperties properties;
    private final SpeechSynthesisService service;

    public SpeechSynthesisController(
            VoiceIdentityResolver identityResolver,
            VoiceRequestValidator validator,
            cn.jia.chat.voice.config.VoiceSpeechProperties properties,
            SpeechSynthesisService service) {
        this.identityResolver = identityResolver;
        this.validator = validator;
        this.properties = properties;
        this.service = service;
    }

    @PostMapping(path = "/synthesis", consumes = "application/json", produces = "audio/mpeg")
    public ResponseEntity<byte[]> synthesize(
            @RequestBody VoiceSynthesisRequest rawRequest, Authentication authentication) {
        VoiceIdentity identity = identityResolver.resolve(authentication);
        VoiceSynthesisRequest request = validator.synthesis(rawRequest, properties.getSynthesis());
        SpeechSynthesisResult result = service.synthesize(identity, request);
        byte[] audio = result.audio();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.noStore())
                .header("X-Voice-Request-Id", request.requestId())
                .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(audio.length))
                .body(audio);
    }
}
