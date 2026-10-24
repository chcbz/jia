package cn.jia.agent.api;

import cn.jia.agent.position.*;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.jia.agent.position.PositionSkillException.require;
import static cn.jia.agent.position.PositionSkillState.*;

@RestController
@RequestMapping("/agent/positions/v1")
@ConditionalOnProperty(prefix="agent.position-skills",name="enabled",havingValue="true")
public class PositionSkillController {
    public record DefineSkill(String skillKey, SkillType type, String productVersionId) { }
    public record Configure(String positionId,String title,int capacity,String resource,boolean allowPublish,
            List<String> skillKeys,String expectedRevision) { }
    public record Appoint(String positionId,String agentId) { }
    private final PositionSkillService service;
    public PositionSkillController(PositionSkillService service){this.service=service;}
    @GetMapping public ResponseEntity<?> read(Authentication auth){return ok(service.read(scope(auth)));}
    @GetMapping("/operations/request") public ResponseEntity<?> recover(Authentication auth,@RequestHeader("Idempotency-Key")String key){
        return ok(service.recovery(scope(auth),key));
    }
    @PostMapping("/skills") public ResponseEntity<?> define(Authentication auth,@RequestHeader("Idempotency-Key")String key,
            @RequestBody JsonRequest<DefineSkill> request){
        var d=data(request);return ok(service.defineSkill(scope(auth),key,new Skill(d.skillKey(),d.type(),d.productVersionId())));
    }
    @PostMapping("/definitions") public ResponseEntity<?> configure(Authentication auth,@RequestHeader("Idempotency-Key")String key,
            @RequestBody JsonRequest<Configure> request){
        var d=data(request);require(d.skillKeys()!=null,400,"POSITION_INVALID");
        long rev=decimal(d.expectedRevision());
        return ok(service.configure(scope(auth),key,new Position(d.positionId(),d.title(),d.capacity(),d.resource(),d.allowPublish(),d.skillKeys(),rev),rev));
    }
    @PostMapping("/appointments") public ResponseEntity<?> appoint(Authentication auth,@RequestHeader("Idempotency-Key")String key,
            @RequestBody JsonRequest<Appoint> request){
        var d=data(request);return ok(service.appoint(scope(auth),key,d.positionId(),d.agentId()));
    }
    @PostMapping("/appointments/{id}/resign") public ResponseEntity<?> resign(Authentication auth,@RequestHeader("Idempotency-Key")String key,@PathVariable String id){
        return ok(service.resign(scope(auth),key,id));
    }
    @PostMapping("/installations/{id}/uninstall") public ResponseEntity<?> uninstall(Authentication auth,@RequestHeader("Idempotency-Key")String key,@PathVariable String id){
        return ok(service.uninstall(scope(auth),key,id));
    }
    @PostMapping("/installations/{id}/retry") public ResponseEntity<?> retry(Authentication auth,@RequestHeader("Idempotency-Key")String key,@PathVariable String id){
        return ok(service.retry(scope(auth),key,id));
    }
    private static Scope scope(Authentication auth){var s=AgentController.requireJwtScope(auth);return new Scope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private static <T>T data(JsonRequest<T> r){require(r!=null && r.data()!=null,400,"POSITION_REQUEST_INVALID");return r.data();}
    private static long decimal(String value){
        require(value!=null && value.matches("0|[1-9][0-9]*"),400,"POSITION_REVISION_INVALID");
        try{return Long.parseLong(value);}catch(NumberFormatException e){throw new PositionSkillException(400,"POSITION_REVISION_INVALID");}
    }
    // Decimal identities/versions are strings on the wire; no unsafe JSON long coercion.
    private static ResponseEntity<?> ok(PositionSkillService.View v){
        var data=new LinkedHashMap<String,Object>();data.put("operationId",v.operationId());data.put("resultId",v.resultId());
        data.put("revision",Long.toString(v.revision()));data.put("skills",v.state().skills().values());
        data.put("positions",v.state().positions().values().stream().map(p->Map.of("positionId",p.id(),"title",p.title(),
                "capacity",p.capacity(),"resource",p.resource(),"allowPublish",p.allowPublish(),"skillKeys",p.skillKeys(),"revision",Long.toString(p.revision()))).toList());
        data.put("appointments",v.state().appointments().values().stream().map(a->Map.of("appointmentId",a.id(),"positionId",a.positionId(),
                "agentId",a.agentId(),"bindingId",Long.toString(a.bindingId()),"active",a.active(),"revision",Long.toString(a.revision()))).toList());
        data.put("installations",v.state().installations().values().stream().map(i->{
            var row=new LinkedHashMap<String,Object>();row.put("installationId",i.id());row.put("agentId",i.agentId());
            row.put("bindingId",Long.toString(i.bindingId()));row.put("skillKey",i.skillKey());row.put("retained",i.retained());
            row.put("desired",i.desired());row.put("revision",Long.toString(i.revision()));row.put("state",i.state());
            row.put("usable",i.usable());row.put("errorCode",i.errorCode());return row;
        }).toList());
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(data));
    }
    @ExceptionHandler(PositionSkillException.class) public ResponseEntity<?> reject(PositionSkillException e){
        return ResponseEntity.status(e.status()).header("Cache-Control","private, no-store").body(JsonResult.failure(e.getMessage(),e.getMessage()));
    }
}
