package cn.jia.chat.archive.controller;

import cn.jia.agent.api.AgentController;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.chat.archive.service.ArchiveMaintenanceService;
import cn.jia.core.entity.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import static cn.jia.agent.position.PositionSkillException.require;

@RestController
@RequestMapping("/archive/admin/v1/jobs")
@ConditionalOnProperty(prefix="agent.position-skills",name="enabled",havingValue="true")
public class ArchiveMaintenanceController {
    private final ArchiveMaintenanceService service;
    public ArchiveMaintenanceController(ArchiveMaintenanceService service){this.service=service;}
    @GetMapping public ResponseEntity<?> list(Authentication auth){return ok(service.list(scope(auth)));}
    @GetMapping("/operations/request") public ResponseEntity<?> recover(Authentication auth,@RequestHeader("Idempotency-Key")String key){return ok(service.recover(scope(auth),key));}
    @GetMapping("/{id}") public ResponseEntity<?> read(Authentication auth,@PathVariable String id){return ok(service.read(scope(auth),id));}
    @GetMapping("/{id}/draft") public ResponseEntity<?> draft(Authentication auth,@PathVariable String id){return ok(service.draft(scope(auth),id));}
    @PostMapping public ResponseEntity<?> create(Authentication auth,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonRequest<ArchiveMaintenanceService.Create> request){
        require(request!=null && request.data()!=null,400,"ARCHIVE_REQUEST_INVALID");return ok(service.create(scope(auth),key,request.data()));
    }
    @PostMapping("/{id}/retry") public ResponseEntity<?> retry(Authentication auth,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonRequest<java.util.Map<String,Object>> request){
        require(request!=null && request.data()!=null && request.data().isEmpty(),400,"ARCHIVE_REQUEST_INVALID");return ok(service.retry(scope(auth),key,id));
    }
    private static Scope scope(Authentication a){var s=AgentController.requireJwtScope(a);return new Scope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private static ResponseEntity<?> ok(Object d){return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(d));}
    @ExceptionHandler(PositionSkillException.class) public ResponseEntity<?> reject(PositionSkillException e){return ResponseEntity.status(e.status()).body(JsonResult.failure(e.getMessage(),e.getMessage()));}
}
