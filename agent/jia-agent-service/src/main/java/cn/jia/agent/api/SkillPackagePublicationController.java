package cn.jia.agent.api;
import cn.jia.agent.skill.*;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Owner acts for an explicitly selected appointed Agent; roles do not grant publication. */
@RestController
public final class SkillPackagePublicationController {
    private final SkillPackagePublicationService publisher;
    public SkillPackagePublicationController(SkillPackagePublicationService publisher){this.publisher=publisher;}
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS=Set.of("productId","productVersionId","name","description","skillKey","skillVersion","priceWen","deploymentRestriction","approvedPermissionsManifest","packageSha256","manifestSha256","expectedProductVersion","positionAuthority");
    @PostMapping(value="/agent/skill-maintenance/product-versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> publish(Authentication authentication,HttpServletRequest request,@RequestPart("metadata") String metadata,@RequestPart("package") MultipartFile archive) throws java.io.IOException {
        HostingRentHttp.noQuery(request);var actor=HostingRentHttp.actor(authentication);
        if(archive==null||archive.getSize()<1||archive.getSize()>SkillPackageStore.MAX_PACKAGE_BYTES)throw new IllegalArgumentException();
        var node=parse(metadata);
        var receipt=publisher.publish(new PositionAuthority.Scope(actor.tenantId(),actor.clientId(),actor.ownerJiacn()),
                grant(node),command(node),archive.getBytes());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.success(receipt));
    }
    @PutMapping(value="/agent/skill-maintenance/product-metadata",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateMetadata(Authentication authentication,HttpServletRequest request,@RequestBody String raw) {
        HostingRentHttp.noQuery(request);var actor=HostingRentHttp.actor(authentication);
        var node=JSON.readTree(raw);if(node==null||!node.isObject())throw new IllegalArgumentException();
        var keys=new HashSet<String>();node.propertyNames().forEach(keys::add);
        if(!keys.equals(Set.of("productId","name","description","expectedProductVersion","positionAuthority")))throw new IllegalArgumentException();
        var receipt=publisher.updateMetadata(new PositionAuthority.Scope(actor.tenantId(),actor.clientId(),actor.ownerJiacn()),
                grant(node),new SkillPackagePublicationService.UpdateMetadata(text(node,"productId"),text(node,"name"),
                        text(node,"description"),decimal(node,"expectedProductVersion")));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.success(receipt));
    }
    @PutMapping(value="/agent/skill-maintenance/product-retirement",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> retireProduct(Authentication authentication,HttpServletRequest request,@RequestBody String raw) {
        HostingRentHttp.noQuery(request);var actor=HostingRentHttp.actor(authentication);
        var node=JSON.readTree(raw);if(node==null||!node.isObject())throw new IllegalArgumentException();
        var keys=new HashSet<String>();node.propertyNames().forEach(keys::add);
        if(!keys.equals(Set.of("productId","expectedProductVersion","positionAuthority")))throw new IllegalArgumentException();
        var receipt=publisher.retireProduct(new PositionAuthority.Scope(actor.tenantId(),actor.clientId(),actor.ownerJiacn()),
                grant(node),new SkillPackagePublicationService.RetireProduct(text(node,"productId"),decimal(node,"expectedProductVersion")));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.success(receipt));
    }
    private static JsonNode parse(String raw) {
        var node=JSON.readTree(raw);if(node==null||!node.isObject())throw new IllegalArgumentException();var keys=new HashSet<String>();node.propertyNames().forEach(keys::add);keys.remove("coveredUpgradeFromProductVersionId");if(!keys.equals(FIELDS))throw new IllegalArgumentException();
        return node;
    }
    private static SkillPackagePublicationService.Publish command(JsonNode node) {
        var manifest=node.get("manifestSha256");if(manifest==null||!manifest.isNull()&&!manifest.isTextual())throw new IllegalArgumentException();
        return new SkillPackagePublicationService.Publish(text(node,"productId"),text(node,"productVersionId"),text(node,"name"),text(node,"description"),text(node,"skillKey"),text(node,"skillVersion"),decimal(node,"priceWen"),text(node,"deploymentRestriction"),text(node,"approvedPermissionsManifest"),text(node,"packageSha256"),manifest.isNull()?null:manifest.textValue(),decimal(node,"expectedProductVersion"),optionalText(node,"coveredUpgradeFromProductVersionId"));
    }
    private static String optionalText(JsonNode node,String key){var value=node.get(key);return value==null||value.isNull()?null:text(node,key);}
    private static PositionAuthority.Grant grant(JsonNode node) {
        var value=node.get("positionAuthority");
        if(value==null||!value.isObject())throw new IllegalArgumentException();
        var fields=new HashSet<String>();value.propertyNames().forEach(fields::add);
        if(!fields.equals(Set.of("appointmentId","appointmentRevision","positionRevision","agentId","bindingId")))throw new IllegalArgumentException();
        return new PositionAuthority.Grant(text(value,"appointmentId"),decimal(value,"appointmentRevision"),decimal(value,"positionRevision"),
                text(value,"agentId"),decimal(value,"bindingId"),null,true);
    }
    private static String text(JsonNode node,String key){var v=node.get(key);if(v==null||!v.isTextual())throw new IllegalArgumentException();return v.textValue();}
    private static long decimal(JsonNode node,String key){String text=text(node,key);if(!text.matches("0|[1-9][0-9]*"))throw new IllegalArgumentException();return Long.parseLong(text);}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failure(Exception failure){int status=failure instanceof cn.jia.agent.hosting.HostingRentApplicationException h?h.status():failure instanceof PositionSkillException p?p.status():failure instanceof SkillMarketplaceException e?e.status():failure instanceof IllegalArgumentException||failure instanceof org.springframework.http.converter.HttpMessageNotReadableException?400:503;String code=failure instanceof cn.jia.agent.hosting.HostingRentApplicationException h?(h.status()==401?"UNAUTHENTICATED":"FORBIDDEN"):failure instanceof PositionSkillException p?p.getMessage():failure instanceof SkillMarketplaceException e?e.code():status==400?"BAD_REQUEST":"SKILL_PUBLICATION_UNAVAILABLE";return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.failure(code,"Skill publication unavailable"));}
}
