package cn.jia.agent.api;
import cn.jia.agent.skill.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public final class SkillMarketplaceController {
    private final SkillMarketplaceService skills;
    public SkillMarketplaceController(SkillMarketplaceService skills) { this.skills=skills; }
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
        return ok(skills.quote(HostingRentHttp.actor(auth),HostingRentHttp.key(request),SkillMarketplaceHttp.body(body,false),admin(auth)));
    }
    @PostMapping(value="/agent/skill-orders",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Object purchase(Authentication auth,HttpServletRequest request,@RequestBody byte[] body) {
        return ok(skills.purchase(HostingRentHttp.actor(auth),HostingRentHttp.key(request),SkillMarketplaceHttp.body(body,true),admin(auth)));
    }
    @GetMapping("/agent/skill-orders/{orderId}")
    public Object order(@PathVariable String orderId,Authentication auth,HttpServletRequest request) {
        HostingRentHttp.noQuery(request); return ok(skills.order(HostingRentHttp.actor(auth),orderId));
    }
    @GetMapping("/agent/{agentId}/skill-entitlements")
    public Object entitlements(@PathVariable String agentId,Authentication auth,HttpServletRequest request) {
        HostingRentHttp.noQuery(request); return ok(skills.entitlements(HostingRentHttp.actor(auth),agentId));
    }
    private static boolean admin(Authentication a) {
        return a!=null && a.isAuthenticated() && a.getAuthorities().stream().anyMatch(x->"ROLE_ADMIN".equals(x.getAuthority()));
    }
    static Object ok(Object data) { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.success(data)); }
    @ExceptionHandler(RuntimeException.class)
    public Object failure(RuntimeException error) {
        int status=503; String code="SKILL_STATE_UNAVAILABLE";
        if(error instanceof SkillMarketplaceException e) { status=e.status();code=e.code(); }
        else if(error instanceof HostingRentApplicationException e) {
            status=e.status(); code=status==401?"UNAUTHENTICATED":status==400?"BAD_REQUEST":"AGENT_OWNER_UNPROVEN";
        } else if(error instanceof EconomyPostingException e) {
            code=e.reason().name(); status="INSUFFICIENT_FUNDS".equals(code)?422:409;
        } else if(error instanceof IllegalArgumentException || error instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            status=400;code="BAD_REQUEST";
        }
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.failure(code,"Skill operation unavailable"));
    }
}
