package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskGrantInputDTO;
import cn.jia.agent.entity.AgentTaskProviderCostConsentDTO;
import cn.jia.agent.entity.AgentTaskProviderCostConsentIssueDTO;
import cn.jia.agent.service.AgentTaskProviderCostConsentService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.security.TenantClaimPolicy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact owner-only, no-store consent issuer. It never assigns, starts or calls a Provider. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks/{taskId}/point-and-start-cost-consents")
public final class AgentTaskProviderCostConsentController {
    static final String CACHE_CONTROL = "private, no-store";
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private static final Set<String> ISSUE_FIELDS=Set.of("schemaVersion",
            "assignmentIdempotencyKey","assignment","providerBinding","acknowledgement");
    private static final Set<String> BINDING_FIELDS=Set.of("bindingId","bindingEpoch");
    private static final Set<String> ASSIGNMENT_FIELDS=Set.of("workflowVersion","businessAction",
            "expectedTaskVersion","requirementRevision","agentId","requestedOperations",
            "initialOperation","inputRefs");
    private static final Set<String> INPUT_FIELDS=Set.of("fileId","version","purpose");
    private static final Set<String> REVOKE_FIELDS=Set.of("expectedVersion");
    private static final ObjectMapper STRICT_JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final AgentTaskProviderCostConsentService consents;
    public AgentTaskProviderCostConsentController(AgentTaskProviderCostConsentService consents) {
        this.consents=Objects.requireNonNull(consents,"consents");
    }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskProviderCostConsentDTO> issue(@PathVariable String taskId,
            @RequestBody(required=false) String rawBody,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            HttpServletRequest request,Authentication authentication) {
        AgentTaskProviderCostConsentService.Scope scope=scope(authentication);
        requirePath(taskId); requireSingleKeyAndNoQuery(request,key);
        var result=consents.issue(scope,taskId,key,issueBody(rawBody));
        if (result==null || result.receipt()==null) throw failure(
                AgentTaskProviderCostConsentService.Reason.SOURCE_UNAVAILABLE);
        return ResponseEntity.status(result.replay()?HttpStatus.OK:HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(result.receipt());
    }

    @GetMapping(value="/{consentId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskProviderCostConsentDTO> get(@PathVariable String taskId,
            @PathVariable String consentId,HttpServletRequest request,
            Authentication authentication) {
        AgentTaskProviderCostConsentService.Scope scope=scope(authentication);
        requirePath(taskId); requirePath(consentId); requireNoQuery(request);
        return ok(consents.get(scope,taskId,consentId));
    }

    @GetMapping(value="/request",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskProviderCostConsentDTO> request(@PathVariable String taskId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            HttpServletRequest request,Authentication authentication) {
        AgentTaskProviderCostConsentService.Scope scope=scope(authentication);
        requirePath(taskId); requireSingleKeyAndNoQuery(request,key);
        return ok(consents.getByIdempotencyKey(scope,taskId,key));
    }

    @PostMapping(value="/{consentId}/revoke",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskProviderCostConsentDTO> revoke(@PathVariable String taskId,
            @PathVariable String consentId,@RequestBody(required=false) String rawBody,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            HttpServletRequest request,Authentication authentication) {
        AgentTaskProviderCostConsentService.Scope scope=scope(authentication);
        requirePath(taskId); requirePath(consentId); requireSingleKeyAndNoQuery(request,key);
        return ok(consents.revoke(scope,taskId,consentId,key,expectedVersion(rawBody)));
    }

    private static ResponseEntity<AgentTaskProviderCostConsentDTO> ok(
            AgentTaskProviderCostConsentDTO body) {
        if (body==null) throw failure(AgentTaskProviderCostConsentService.Reason.SOURCE_UNAVAILABLE);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden?HttpStatus.FORBIDDEN:HttpStatus.UNAUTHORIZED,
                failure.forbidden?"PROVIDER_CONSENT_FORBIDDEN":"UNAUTHENTICATED",
                failure.forbidden?"Provider consent scope is unavailable":"Authentication is required");
    }
    @ExceptionHandler(AgentTaskProviderCostConsentService.Failure.class)
    public ResponseEntity<ErrorBody> failure(AgentTaskProviderCostConsentService.Failure failure) {
        return switch(failure.reason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST,"BAD_REQUEST","Invalid provider consent request");
            case FORBIDDEN -> error(HttpStatus.FORBIDDEN,"PROVIDER_CONSENT_FORBIDDEN","Provider consent is not permitted");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND,"PROVIDER_CONSENT_NOT_FOUND","Provider consent is unavailable");
            case CONFLICT -> error(HttpStatus.CONFLICT,"PROVIDER_CONSENT_CONFLICT","Provider consent state changed or conflicts");
            case SOURCE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,"PROVIDER_CONSENT_UNAVAILABLE","Provider consent source is temporarily unavailable");
        };
    }
    @ExceptionHandler({InvalidRequest.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST,"BAD_REQUEST","Invalid provider consent request");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception failure) {
        log.error("Provider consent failed: type={}",failure.getClass().getName());
        return error(HttpStatus.SERVICE_UNAVAILABLE,"PROVIDER_CONSENT_UNAVAILABLE",
                "Provider consent source is temporarily unavailable");
    }

    private static AgentTaskProviderCostConsentIssueDTO issueBody(String raw) {
        if (raw==null || raw.isBlank()) throw new InvalidRequest();
        try {
            JsonNode root=STRICT_JSON.readTree(raw);
            if (root==null || !root.isObject() || !fields(root).equals(ISSUE_FIELDS)) throw new InvalidRequest();
            if (!integral(root.get("schemaVersion"),1) || !text(root.get("assignmentIdempotencyKey"))
                    || !text(root.get("acknowledgement"))) throw new InvalidRequest();
            JsonNode binding=root.get("providerBinding");
            if (binding==null || !binding.isObject() || !fields(binding).equals(BINDING_FIELDS)
                    || !text(binding.get("bindingId")) || !text(binding.get("bindingEpoch"))) throw new InvalidRequest();
            JsonNode assignment=root.get("assignment");
            if (assignment==null || !assignment.isObject()
                    || !fields(assignment).equals(ASSIGNMENT_FIELDS)) throw new InvalidRequest();
            AgentTaskAssignDTO assign=parseAssignment(assignment);
            AgentTaskProviderCostConsentIssueDTO result=new AgentTaskProviderCostConsentIssueDTO();
            result.setSchemaVersion(root.get("schemaVersion").intValue());
            result.setAssignmentIdempotencyKey(root.get("assignmentIdempotencyKey").textValue());
            result.setAssignment(assign);
            result.setProviderBinding(new AgentTaskProviderCostConsentIssueDTO.ProviderBinding(
                    binding.get("bindingId").textValue(),binding.get("bindingEpoch").textValue()));
            result.setAcknowledgement(root.get("acknowledgement").textValue());
            return result;
        } catch (InvalidRequest failure) { throw failure; }
        catch (Exception failure) { throw new InvalidRequest(); }
    }

    private static AgentTaskAssignDTO parseAssignment(JsonNode node) {
        if (!integral(node.get("workflowVersion"),2) || !text(node.get("businessAction"))
                || !safeIntegral(node.get("expectedTaskVersion"),0)
                || !safeIntegral(node.get("requirementRevision"),1)
                || !text(node.get("agentId")) || !text(node.get("initialOperation"))) throw new InvalidRequest();
        JsonNode operations=node.get("requestedOperations");
        if (operations==null || !operations.isArray() || operations.size()!=1
                || !text(operations.get(0))) throw new InvalidRequest();
        JsonNode refs=node.get("inputRefs");
        if (refs==null || !refs.isArray() || refs.size()>16) throw new InvalidRequest();
        List<AgentTaskGrantInputDTO> inputs=new ArrayList<>(); Set<String> unique=new HashSet<>();
        for (JsonNode item:refs) {
            if (!item.isObject() || !fields(item).equals(INPUT_FIELDS)
                    || !text(item.get("fileId")) || !safeIntegral(item.get("version"),1)
                    || !text(item.get("purpose"))) throw new InvalidRequest();
            String identity=item.get("fileId").textValue()+"\u0000"+item.get("version").intValue()
                    +"\u0000"+item.get("purpose").textValue();
            if (!unique.add(identity)) throw new InvalidRequest();
            AgentTaskGrantInputDTO input=new AgentTaskGrantInputDTO();
            input.setFileId(item.get("fileId").textValue());input.setVersion(item.get("version").intValue());
            input.setPurpose(item.get("purpose").textValue());inputs.add(input);
        }
        AgentTaskAssignDTO result=new AgentTaskAssignDTO();
        result.setWorkflowVersion(node.get("workflowVersion").intValue());
        result.setBusinessAction(node.get("businessAction").textValue());
        result.setExpectedTaskVersion(node.get("expectedTaskVersion").longValue());
        result.setRequirementRevision(node.get("requirementRevision").longValue());
        result.setAgentId(node.get("agentId").textValue());
        result.setRequestedOperations(List.of(operations.get(0).textValue()));
        result.setInitialOperation(node.get("initialOperation").textValue());
        result.setInputRefs(List.copyOf(inputs)); return result;
    }

    private static long expectedVersion(String raw) {
        if (raw==null || raw.isBlank()) throw new InvalidRequest();
        try {
            JsonNode root=STRICT_JSON.readTree(raw);
            if (root==null || !root.isObject() || !fields(root).equals(REVOKE_FIELDS)
                    || !text(root.get("expectedVersion"))) throw new InvalidRequest();
            String value=root.get("expectedVersion").textValue();
            if (!value.matches("[1-9][0-9]*")) throw new InvalidRequest();
            long parsed=Long.parseLong(value);
            if (parsed>MAX_SAFE_INTEGER || !Long.toString(parsed).equals(value)) throw new InvalidRequest();
            return parsed;
        } catch (InvalidRequest failure) { throw failure; }
        catch (Exception failure) { throw new InvalidRequest(); }
    }

    private static AgentTaskProviderCostConsentService.Scope scope(Authentication authentication) {
        if (authentication==null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) throw new AuthenticationFailure(false);
        Map<String,Object> claims=jwt.getToken().getClaims(); Object owner=claims.get("jiacn");
        Object client=claims.get("client_id"); final String tenant;
        try { tenant=TenantClaimPolicy.resolve(claims,"0"); }
        catch (IllegalArgumentException invalid) { throw new AuthenticationFailure(true); }
        EsContext context=EsContextHolder.getContext();
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !valid(ownerJiacn,50) || "0".equals(ownerJiacn) || !valid(clientId,50)
                || !"0".equals(tenant) || context==null || !"0".equals(context.getTenantId())
                || !Objects.equals(ownerJiacn,context.getJiacn())
                || !Objects.equals(clientId,context.getClientId())) throw new AuthenticationFailure(true);
        return new AgentTaskProviderCostConsentService.Scope("0",clientId,ownerJiacn);
    }
    private static void requireSingleKeyAndNoQuery(HttpServletRequest request,String key) {
        if (!valid(key,100) || !request.getParameterMap().isEmpty()) throw new InvalidRequest();
        List<String> values=Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size()!=1 || !Objects.equals(key,values.getFirst())) throw new InvalidRequest();
    }
    private static void requireNoQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new InvalidRequest();
    }
    private static void requirePath(String value) { if (!valid(value,100)) throw new InvalidRequest(); }
    private static boolean valid(String value,int max) {
        return value!=null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0,value.length())<=max
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static boolean text(JsonNode value) { return value!=null && value.isTextual(); }
    private static boolean integral(JsonNode value,int expected) {
        return value!=null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue()==expected;
    }
    private static boolean safeIntegral(JsonNode value,long min) {
        return value!=null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue()>=min && value.longValue()<=MAX_SAFE_INTEGER;
    }
    private static Set<String> fields(JsonNode node) {
        Set<String> result=new HashSet<>();node.propertyNames().forEach(result::add);return result;
    }
    private static AgentTaskProviderCostConsentService.Failure failure(
            AgentTaskProviderCostConsentService.Reason reason) {
        return new AgentTaskProviderCostConsentService.Failure(reason);
    }
    private static ResponseEntity<ErrorBody> error(HttpStatus status,String code,String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(code,message));
    }
    public record ErrorBody(String code,String message) { }
    private static final class InvalidRequest extends RuntimeException { }
    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden; private AuthenticationFailure(boolean forbidden){this.forbidden=forbidden;}
    }
}
