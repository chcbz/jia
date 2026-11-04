package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.skill.SkillMarketplaceException;
import cn.jia.agent.skill.SkillPackagePublicationService;
import cn.jia.chat.service.PrivateConversationToolAccess.Denied;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.LinkedHashMap;

/** Runtime transport only: neither OAuth identities nor model-supplied scope grant access. */
@RestController
@RequestMapping("/internal/agent/chat/requests/{requestId}/turns/{turnId}/tools")
public final class AgentConversationToolsController {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final SkillConversationToolAdapter service;
    public AgentConversationToolsController(SkillConversationToolAdapter service) {this.service=service;}
    @GetMapping
    public ResponseEntity<?> catalog(Authentication principal,@PathVariable String requestId,@PathVariable String turnId,HttpServletRequest request) {
        noQuery(request);return ok(service.catalog(principal,requestId,turnId));
    }
    @PostMapping("/{tool}")
    public ResponseEntity<?> call(Authentication principal,@PathVariable String requestId,@PathVariable String turnId,@PathVariable String tool,
            @cn.jia.core.annotation.ApiJsonData @RequestBody String raw,HttpServletRequest request) {
        noQuery(request);
        return ok(wire(SkillConversationTools.call(service,principal,requestId,turnId,tool,JSON.readTree(raw))));
    }
    private static Object wire(Object value) {
        var result=new LinkedHashMap<String,Object>();
        if(value instanceof SkillPackagePublicationService.MetadataReceipt r) {
            result.put("productId",r.productId());result.put("name",r.name());result.put("description",r.description());
            result.put("productVersion",Long.toString(r.productVersion()));result.put("disposition",r.disposition());
        } else if(value instanceof SkillPackagePublicationService.RetirementReceipt r) {
            result.put("productId",r.productId());result.put("status",r.status());result.put("productVersion",Long.toString(r.productVersion()));result.put("disposition",r.disposition());
        } else if(value instanceof SkillPackagePublicationService.Receipt r) {
            result.put("productId",r.productId());result.put("productVersionId",r.productVersionId());result.put("productVersion",Long.toString(r.productVersion()));
            result.put("packageSha256",r.packageSha256());result.put("manifestSha256",r.manifestSha256());result.put("disposition",r.disposition());
        } else return value;
        return result;
    }
    private static void noQuery(HttpServletRequest request) {if(request.getQueryString()!=null)throw new IllegalArgumentException();}
    private static ResponseEntity<?> ok(Object value) {return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(value));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failure(Exception failure) {
        int status=failure instanceof SkillMarketplaceException e?e.status():failure instanceof PositionSkillException p?p.status()
                :failure instanceof Denied?403:failure instanceof IllegalArgumentException
                ||failure instanceof tools.jackson.core.JacksonException
                ||failure instanceof org.springframework.http.converter.HttpMessageNotReadableException?400:503;
        String code=failure instanceof SkillMarketplaceException e?e.code():failure instanceof PositionSkillException p?p.getMessage()
                :status==403?"CONVERSATION_TOOL_FORBIDDEN":status==400?"BAD_REQUEST":"CONVERSATION_TOOL_UNAVAILABLE";
        return ResponseEntity.status(status).header("Cache-Control","private, no-store").body(JsonResult.failure(code,"Conversation tool unavailable"));
    }
}
