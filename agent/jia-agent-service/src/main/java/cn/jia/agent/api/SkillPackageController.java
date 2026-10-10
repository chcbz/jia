package cn.jia.agent.api;
import cn.jia.agent.skill.SkillInstallResultService;
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
    private final SkillInstallResultService installs;
    public SkillPackageController(SkillInstallResultService installs) { this.installs=installs; }
    @GetMapping("/internal/agent/skill-installations/{installationId}/package")
    public ResponseEntity<byte[]> download(@PathVariable String installationId,Authentication auth,HttpServletRequest request) {
        HostingRentHttp.noQuery(request);
        byte[] bytes=installs.packageBytes(AgentRuntimeAuthenticationService.requireProof(auth),installationId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(bytes.length).body(bytes);
    }
    @ExceptionHandler(RuntimeException.class)
    public Object failure(RuntimeException ignored) {
        return ResponseEntity.status(403).header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .body(JsonResult.failure("SKILL_DOWNLOAD_FORBIDDEN","Package access unavailable"));
    }
}
