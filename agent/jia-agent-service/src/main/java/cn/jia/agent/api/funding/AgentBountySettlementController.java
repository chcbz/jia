package cn.jia.agent.api.funding;

import cn.jia.core.security.TenantClaimPolicy;


import cn.jia.agent.service.funding.FundedBountyActor;
import cn.jia.agent.service.funding.FundedBountyException;
import cn.jia.agent.service.funding.FundedBountySettlementService;
import cn.jia.core.entity.JsonResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/agent/tasks/{taskId}")
public class AgentBountySettlementController {
    private final ObjectProvider<FundedBountySettlementService> services;
    public AgentBountySettlementController(ObjectProvider<FundedBountySettlementService> services) { this.services = services; }

    @GetMapping("/settlement")
    public ResponseEntity<?> read(@PathVariable String taskId, Authentication authentication) {
        FundedBountyActor actor = actor(authentication);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(JsonResult.success(service().read(actor, taskId)));
    }

    @ExceptionHandler(FundedBountyException.class)
    public ResponseEntity<JsonResult<Void>> rejected(FundedBountyException failure) {
        JsonResult<Void> result = JsonResult.failure(failure.code(), failure.getMessage());

        return ResponseEntity.status(failure.status()).header("Cache-Control", "private, no-store").body(result);
    }

    private FundedBountySettlementService service() {
        FundedBountySettlementService service = services.getIfAvailable();
        if (service == null) throw new FundedBountyException(HttpStatus.SERVICE_UNAVAILABLE,
                "FUNDED_HISTORY_UNAVAILABLE", "Historical settlement read is unavailable");
        return service;
    }

    private static FundedBountyActor actor(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt) || !auth.isAuthenticated()) {
            throw new FundedBountyException(HttpStatus.UNAUTHORIZED, "ECONOMY_UNAUTHENTICATED", "JWT required");
        }
        String subject = claim(jwt, "sub", 100);
        if (!subject.equals(auth.getName())) throw forbidden();
        try {
            return new FundedBountyActor(TenantClaimPolicy.resolve(jwt.getToken().getClaims()), claim(jwt, "client_id", 50),
                    claim(jwt, "jiacn", 50), subject);
        } catch (IllegalArgumentException denied) {
            throw forbidden();
        }
    }

    private static String claim(JwtAuthenticationToken jwt, String name, int limit) {
        Object value = jwt.getToken().getClaims().get(name);
        if (!(value instanceof String text) || text.isEmpty() || !text.equals(text.strip())
                || text.getBytes(StandardCharsets.UTF_8).length > limit || text.codePoints().anyMatch(Character::isISOControl)) throw forbidden();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw forbidden();
            } else if (Character.isLowSurrogate(ch)) throw forbidden();
        }
        return text;
    }
    private static FundedBountyException forbidden() {
        return new FundedBountyException(HttpStatus.FORBIDDEN, "ECONOMY_FORBIDDEN", "Exact monetary JWT scope required");
    }
}
