package cn.jia.agent.api;

import cn.jia.agent.position.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static cn.jia.agent.position.PositionSkillException.require;

@RestController
@RequestMapping("/internal/agent/position-skills")
@ConditionalOnProperty(prefix="agent.position-skills",name="enabled",havingValue="true")
public class PositionSkillRuntimeController {
    private final PositionSkillRuntimeService service;
    private final AgentRuntimeAuthenticationService authentication;
    public PositionSkillRuntimeController(PositionSkillRuntimeService service,AgentRuntimeAuthenticationService authentication){this.service=service;this.authentication=authentication;}
    @GetMapping public ResponseEntity<?> commands(Authentication principal){
        var proof=authentication.requireProof(principal);
        var prepared=service.prepare(PositionSkillRuntimeService.scope(proof),proof.scope().agentId());
        return authentication.withNativeFence(principal,()->ok(service.commands(proof,prepared)));
    }
    @GetMapping("/{id}/receipt") public ResponseEntity<?> outcome(Authentication principal,@PathVariable String id){var proof=authentication.requireProof(principal);return authentication.withNativeFence(principal,()->ok(service.outcome(proof,id)));}
    @PostMapping("/results") public ResponseEntity<?> result(Authentication principal,
            @RequestBody JsonRequest<java.util.Map<String,Object>> request){
        require(request!=null && request.data()!=null,400,"POSITION_RECEIPT_INVALID");
        var proof=authentication.requireProof(principal);
        // Shared management consumer prepares package IO before it acquires its native/skill/position locks.
        return ok(service.managementResult(proof,request.data()));
    }
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(data));}
    @ExceptionHandler(cn.jia.agent.skill.SkillMarketplaceException.class) public ResponseEntity<?> managementReject(cn.jia.agent.skill.SkillMarketplaceException e){
        return ResponseEntity.status(e.status()).header("Cache-Control","private, no-store").body(JsonResult.failure(e.code(),e.code()));
    }
    @ExceptionHandler(PositionSkillException.class) public ResponseEntity<?> reject(PositionSkillException e){
        return ResponseEntity.status(e.status()).header("Cache-Control","private, no-store").body(JsonResult.failure(e.getMessage(),e.getMessage()));
    }
}
