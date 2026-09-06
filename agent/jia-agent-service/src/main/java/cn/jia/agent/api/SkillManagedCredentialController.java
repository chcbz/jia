package cn.jia.agent.api;
import cn.jia.agent.skill.SkillManagedCredentials;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
@RestController
public final class SkillManagedCredentialController {
    private final SkillManagedCredentials credentials;
    private final SkillMarketplaceController errors;
    public SkillManagedCredentialController(SkillManagedCredentials credentials,SkillMarketplaceController errors) { this.credentials=credentials;this.errors=errors; }
    @cn.jia.core.security.AllowSensitiveOutput(reason="Explicit one-time exact-Agent credential issuance; no secret replay")
    @PostMapping(value="/agent/{agentId}/skill-install-credentials",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Object rotate(@PathVariable String agentId,Authentication auth,HttpServletRequest request,@RequestBody byte[] raw) {
        var actor=HostingRentHttp.actor(auth);String key=HostingRentHttp.key(request);
        var body=HostingRentHttp.body(raw,Set.of("expectedAgentVersion"),Set.of());
        long version=HostingRentHttp.positive(body.get("expectedAgentVersion"));
        return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("Pragma","no-cache").body(JsonResult.success(credentials.rotate(actor,agentId,key,version)));
    }
    @ExceptionHandler(RuntimeException.class) public Object failure(RuntimeException error) { return errors.failure(error); }
}
