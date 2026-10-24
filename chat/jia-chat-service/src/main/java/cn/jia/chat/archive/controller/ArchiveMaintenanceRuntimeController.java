package cn.jia.chat.archive.controller;

import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.chat.archive.service.ArchiveMaintenanceService;
import cn.jia.core.entity.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import static cn.jia.agent.position.PositionSkillException.require;

@RestController
@RequestMapping("/internal/archive/v1/jobs")
@ConditionalOnProperty(prefix="agent.position-skills",name="enabled",havingValue="true")
public class ArchiveMaintenanceRuntimeController {
    private final ArchiveMaintenanceService service;private final AgentRuntimeAuthenticationService auth;
    public ArchiveMaintenanceRuntimeController(ArchiveMaintenanceService service,AgentRuntimeAuthenticationService auth){this.service=service;this.auth=auth;}
    @GetMapping public ResponseEntity<?> commands(Authentication a){var p=auth.requireProof(a);return auth.withNativeFence(a,()->ok(service.commands(p)));}
    @GetMapping("/{id}/source") public ResponseEntity<?> source(Authentication a,@PathVariable String id){var p=auth.requireProof(a);return auth.withNativeFence(a,()->ok(service.source(p,id)));}
    @GetMapping("/{id}/result") public ResponseEntity<?> outcome(Authentication a,@PathVariable String id){var p=auth.requireProof(a);return auth.withNativeFence(a,()->ok(service.outcome(p,id)));}
    @PostMapping("/{id}/result") public ResponseEntity<?> result(Authentication a,@PathVariable String id,@RequestBody JsonRequest<ArchiveMaintenanceService.Result> r){
        require(r!=null && r.data()!=null,400,"ARCHIVE_RESULT_REQUIRED");var p=auth.requireProof(a);return auth.withNativeFence(a,()->ok(service.complete(p,id,r.data())));
    }
    private static ResponseEntity<?> ok(Object d){return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(d));}
    @ExceptionHandler(PositionSkillException.class) public ResponseEntity<?> reject(PositionSkillException e){return ResponseEntity.status(e.status()).body(JsonResult.failure(e.getMessage(),e.getMessage()));}
}
