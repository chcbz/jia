package cn.jia.chat.api;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatInspectionAuthorityService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@RestController
@ConditionalOnProperty(prefix = "chat.typed-inspection", name = "enabled", havingValue = "true")
public final class AgentInspectionInputController {
    private static final String MANIFEST_HEADER = "X-Inspection-Manifest-Digest";
    private final ChatInspectionAuthorityService authority;

    private final AgentRuntimeAuthenticationService runtimeAuthentication;
    public AgentInspectionInputController(ChatInspectionAuthorityService authority,
            AgentRuntimeAuthenticationService runtimeAuthentication) {
        this.authority = Objects.requireNonNull(authority);
        this.runtimeAuthentication = Objects.requireNonNull(runtimeAuthentication);
    }

    @GetMapping("/internal/agent/chat/requests/{requestId}/turns/{turnId}/inspection/inputs/{sourceRefId}/content")
    public void content(@PathVariable String requestId,
            @PathVariable String turnId, @PathVariable String sourceRefId,
            Authentication authentication, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0) throw unavailable();
        if (!(authentication instanceof AgentRuntimeAuthentication runtime) || !runtime.isAuthenticated()) {
            throw new RuntimeAccessException(authentication == null || !authentication.isAuthenticated()
                    ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN);
        }
        List<String> headers = Collections.list(request.getHeaders(MANIFEST_HEADER));
        if (headers.size() != 1) throw unavailable();
        var proof=AgentRuntimeAuthenticationService.requireProof(runtime);
        runtimeAuthentication.validateCurrent(proof,false);
        try(var value=authority.read(runtime.getPrincipal(),requestId,turnId,sourceRefId,headers.getFirst())) {
            runtimeAuthentication.withNativeFence(runtime,()->{
                response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
                response.setContentType(value.mimeType());response.setContentLengthLong(value.byteLength());return null;
            });
            byte[] buffer=new byte[8192];int count;
            var output=response.getOutputStream();
            while((count=value.stream().read(buffer))!=-1)output.write(buffer,0,count);
        }
    }

    @ExceptionHandler(java.io.IOException.class)
    public void transport(java.io.IOException failure,jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        if(!response.isCommitted()){response.reset();response.sendError(503);}
    }

    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> error(ChatDeliberationException failure) {
        HttpStatus status = switch (failure.reason()) {
            case INVALID_REQUEST, INVALID_ROUTE, GAP -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NOT_FOUND_OR_FORBIDDEN -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PERSISTENCE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        JsonResult<Void> result = JsonResult.failure("INSPECTION_INPUT_" + failure.reason(),
                status == HttpStatus.NOT_FOUND
                        ? "Inspection input is unavailable" : failure.getMessage());

        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }

    @ExceptionHandler(RuntimeAccessException.class)
    public ResponseEntity<JsonResult<Void>> forbidden(RuntimeAccessException failure) {
        JsonResult<Void> result = JsonResult.failure(
                failure.status == HttpStatus.UNAUTHORIZED
                        ? "AGENT_RUNTIME_UNAUTHENTICATED" : "AGENT_RUNTIME_FORBIDDEN",
                failure.status == HttpStatus.UNAUTHORIZED
                        ? "Runtime authentication is required" : "Runtime authentication is forbidden");

        return ResponseEntity.status(failure.status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }


    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> runtime(RuntimeException failure) {
        JsonResult<Void> result = JsonResult.failure("INSPECTION_INPUT_UNAVAILABLE",
                "Inspection input is temporarily unavailable");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }

    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Inspection input is unavailable");
    }

    private static final class RuntimeAccessException extends SecurityException {
        private final HttpStatus status;
        private RuntimeAccessException(HttpStatus status) { this.status = status; }
    }
}
