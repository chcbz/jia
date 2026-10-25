package cn.jia.agent.api;
import cn.jia.agent.skill.*;
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

/** Explicit authenticated platform-maintenance entry; never model-directed execution. */
@RestController
public final class SkillPackagePublicationController {
    private final SkillPackagePublicationService publisher;
    public SkillPackagePublicationController(SkillPackagePublicationService publisher){this.publisher=publisher;}
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS=Set.of("productId","productVersionId","name","description","skillKey","skillVersion","priceMicro","deploymentRestriction","approvedPermissionsManifest","packageSha256","manifestSha256","expectedProductVersion");
    @PostMapping(value="/agent/skill-maintenance/product-versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> publish(Authentication authentication,HttpServletRequest request,@RequestPart("metadata") String metadata,@RequestPart("package") MultipartFile archive) throws java.io.IOException {
        HostingRentHttp.noQuery(request);var actor=HostingRentHttp.actor(authentication);
        boolean admin=authentication.getAuthorities().stream().anyMatch(a->"ROLE_ADMIN".equals(a.getAuthority()));
        if(!admin)throw new SkillMarketplaceException(403,"SKILL_PUBLICATION_FORBIDDEN");
        if(archive==null||archive.getSize()<1||archive.getSize()>SkillPackageStore.MAX_PACKAGE_BYTES)throw new IllegalArgumentException();
        var receipt=publisher.publish(actor,true,parse(metadata),archive.getBytes());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.success(receipt));
    }
    private static SkillPackagePublicationService.Publish parse(String raw) {
        var node=JSON.readTree(raw);if(node==null||!node.isObject())throw new IllegalArgumentException();var keys=new HashSet<String>();node.propertyNames().forEach(keys::add);if(!keys.equals(FIELDS))throw new IllegalArgumentException();
        var manifest=node.get("manifestSha256");if(manifest==null||!manifest.isNull()&&!manifest.isTextual())throw new IllegalArgumentException();
        return new SkillPackagePublicationService.Publish(text(node,"productId"),text(node,"productVersionId"),text(node,"name"),text(node,"description"),text(node,"skillKey"),text(node,"skillVersion"),decimal(node,"priceMicro"),text(node,"deploymentRestriction"),text(node,"approvedPermissionsManifest"),text(node,"packageSha256"),manifest.isNull()?null:manifest.textValue(),decimal(node,"expectedProductVersion"));
    }
    private static String text(JsonNode node,String key){var v=node.get(key);if(v==null||!v.isTextual())throw new IllegalArgumentException();return v.textValue();}
    private static long decimal(JsonNode node,String key){String text=text(node,key);if(!text.matches("0|[1-9][0-9]*"))throw new IllegalArgumentException();return Long.parseLong(text);}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failure(Exception failure){int status=failure instanceof SkillMarketplaceException e?e.status():failure instanceof IllegalArgumentException||failure instanceof org.springframework.http.converter.HttpMessageNotReadableException?400:503;String code=failure instanceof SkillMarketplaceException e?e.code():status==400?"BAD_REQUEST":"SKILL_PUBLICATION_UNAVAILABLE";return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.failure(code,"Skill publication unavailable"));}
}
