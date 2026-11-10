package cn.jia.agent.api;

import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.skill.SkillManagementCodec;
import cn.jia.agent.skill.SkillManagementService;
import cn.jia.agent.skill.SkillMarketplaceException;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Collections;

/** Original immutable management results over native HTTP; same service/receipt as WS. */
@RestController
public final class SkillManagementResultController {
    private final SkillManagementService management;
    public SkillManagementResultController(SkillManagementService management) { this.management = management; }

    @PostMapping(value = "/internal/agent/skill-management/results", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Object result(@RequestBody byte[] bytes, Authentication principal, HttpServletRequest request) {
        HostingRentHttp.noQuery(request);
        var envelope = SkillManagementCodec.read(bytes, "HttpRequest");
        String id = SkillManagementCodec.text(envelope, "requestId");
        if (!id.equals(request.getHeader("X-Request-Id")) || Collections.list(request.getHeaders("X-Request-Id")).size() != 1)
            throw new SkillMarketplaceException(400, "BAD_REQUEST");
        RequestIdFilter.accept(request, id);
        var result = SkillManagementCodec.object(envelope.get("data"));
        // The service owns schema, original relation, transaction and idempotent receipt.
        var receipt = management.result(AgentRuntimeAuthenticationService.requireProof(principal), result);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(JsonResult.success(receipt));
    }
}
