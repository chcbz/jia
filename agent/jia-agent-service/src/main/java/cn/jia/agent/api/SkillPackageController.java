package cn.jia.agent.api;
import cn.jia.agent.skill.SkillMarketplaceException;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public final class SkillPackageController {
    private final cn.jia.agent.skill.SkillManagementService installs;
    public SkillPackageController(cn.jia.agent.skill.SkillManagementService installs) { this.installs=installs; }
    @GetMapping("/internal/agent/skill-installations/{installationId}/package")
    public ResponseEntity<byte[]> download(@PathVariable String installationId,Authentication auth,HttpServletRequest request) {
        HostingRentHttp.noQuery(request);cn.jia.agent.skill.SkillManagementCodec.path(installationId);
        if(auth==null||!auth.isAuthenticated())throw new SkillMarketplaceException(401,"UNAUTHENTICATED");
        byte[] bytes=installs.packageBytes(AgentRuntimeAuthenticationService.requireProof(auth),installationId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("X-Content-Type-Options","nosniff").contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length).body(bytes);
    }
    @ExceptionHandler(RuntimeException.class)
    public Object failure(RuntimeException error) {return SkillMarketplaceController.failureResponse(error);}
}
