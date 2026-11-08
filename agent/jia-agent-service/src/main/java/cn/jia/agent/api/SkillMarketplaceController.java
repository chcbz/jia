package cn.jia.agent.api;
import cn.jia.agent.skill.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public final class SkillMarketplaceController {
    private final SkillMarketplaceService skills;
    private SkillManagementService management;
    public SkillMarketplaceController(SkillMarketplaceService skills) { this.skills=skills; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setManagement(SkillManagementService management) {this.management=management;}
    @GetMapping("/economy/capabilities")
    public Object capabilities(Authentication auth,HttpServletRequest request) {
        HostingRentHttp.noQuery(request); return ok(skills.capabilities(HostingRentHttp.actor(auth)));
    }
    @GetMapping("/agent/skill-products")
    public Object catalog(Authentication auth,HttpServletRequest request) {
        HostingRentHttp.noQuery(request); return ok(skills.catalog(HostingRentHttp.actor(auth),admin(auth)));
    }
    @GetMapping("/agent/skill-products/{productId}")
    public Object product(@PathVariable String productId,Authentication auth,HttpServletRequest request) {
        HostingRentHttp.noQuery(request); return ok(skills.detail(HostingRentHttp.actor(auth),productId,admin(auth)));
    }
    @PostMapping(value="/agent/skill-orders/quotes",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Object quote(Authentication auth,HttpServletRequest request,@RequestBody byte[] body) {
        var data=managementBody(request,body,"QuoteRequest");
        return ok(management.quote(HostingRentHttp.actor(auth),managementKey(request),data,admin(auth)));
    }
    @PostMapping("/agent/skill-orders")
    public Object purchase(Authentication auth,HttpServletRequest request,@RequestBody(required=false) byte[] body) {
        HostingRentHttp.actor(auth);
        throw new SkillMarketplaceException(410,"ENDPOINT_RETIRED");
    }
    @GetMapping("/agent/skill-orders/{orderId}")
    public Object order(@PathVariable String orderId,Authentication auth,HttpServletRequest request) {
        HostingRentHttp.noQuery(request); return ok(skills.order(HostingRentHttp.actor(auth),orderId));
    }
    @GetMapping("/agent/{agentId}/skill-entitlements")
    public Object entitlements(@PathVariable String agentId,Authentication auth,HttpServletRequest request) {
        HostingRentHttp.noQuery(request); return ok(skills.entitlements(HostingRentHttp.actor(auth),agentId));
    }
    @GetMapping("/agent/{agentId}/skills")
    public Object skills(@PathVariable String agentId,Authentication auth,HttpServletRequest request) {
        query(request);return ok(management.skills(HostingRentHttp.actor(auth),agentId,admin(auth)));
    }
    @GetMapping("/agent/{agentId}/skill-changes")
    public Object changes(@PathVariable String agentId,Authentication auth,HttpServletRequest request) {
        query(request);return ok(management.changes(HostingRentHttp.actor(auth),agentId));
    }
    @GetMapping("/agent/{agentId}/skill-changes/{skillChangeId}")
    public Object change(@PathVariable String agentId,@PathVariable String skillChangeId,Authentication auth,HttpServletRequest request) {
        query(request);return ok(management.change(HostingRentHttp.actor(auth),agentId,skillChangeId));
    }
    @PostMapping(value="/agent/{agentId}/skill-changes",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Object create(@PathVariable String agentId,Authentication auth,HttpServletRequest request,@RequestBody byte[] body) {
        var data=managementBody(request,body,"CreateChangeRequest");return accepted(management.create(HostingRentHttp.actor(auth),agentId,
            managementKey(request),cn.jia.core.filter.RequestIdFilter.current(request),data,admin(auth)));
    }
    @PostMapping(value="/agent/{agentId}/skill-changes/{skillChangeId}/retry",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Object retry(@PathVariable String agentId,@PathVariable String skillChangeId,Authentication auth,HttpServletRequest request,@RequestBody byte[] body) {
        var data=managementBody(request,body,"RetryRequest");return accepted(management.retry(HostingRentHttp.actor(auth),agentId,skillChangeId,
            managementKey(request),cn.jia.core.filter.RequestIdFilter.current(request),data));
    }
    @PostMapping(value="/agent/{agentId}/skill-changes/{skillChangeId}/reconcile",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Object reconcile(@PathVariable String agentId,@PathVariable String skillChangeId,Authentication auth,HttpServletRequest request,@RequestBody byte[] body) {
        var data=managementBody(request,body,"ReconcileRequest");return accepted(management.reconcile(HostingRentHttp.actor(auth),agentId,skillChangeId,
            managementKey(request),cn.jia.core.filter.RequestIdFilter.current(request),data));
    }
    private static void query(HttpServletRequest request) {
        HostingRentHttp.noQuery(request);
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw new SkillMarketplaceException(400,"BAD_REQUEST");
    }
    private static java.util.Map<String,Object> managementBody(HttpServletRequest request,byte[] body,String definition) {
        HostingRentHttp.noQuery(request);
        var envelope=SkillManagementCodec.read(body,definition);
        String id=(String)envelope.get("requestId");String header=request.getHeader("X-Request-Id");
        if(header==null||!header.equals(id)||java.util.Collections.list(request.getHeaders("X-Request-Id")).size()!=1)
            throw new SkillMarketplaceException(400,"BAD_REQUEST");
        cn.jia.core.filter.RequestIdFilter.accept(request,id);
        return SkillManagementCodec.object(envelope.get("data"));
    }
    private static String managementKey(HttpServletRequest request) {
        var keys=java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1)throw new SkillMarketplaceException(400,"BAD_REQUEST");
        SkillManagementCodec.validate("Id",keys.getFirst());return keys.getFirst();
    }
    private static Object accepted(Object data) {
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.success(data));
    }
    private static boolean admin(Authentication a) {
        return a!=null && a.isAuthenticated() && a.getAuthorities().stream().anyMatch(x->"ROLE_ADMIN".equals(x.getAuthority()));
    }
    static Object ok(Object data) { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.success(data)); }
}
