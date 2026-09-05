package cn.jia.agent.api;

import cn.jia.agent.entity.AgentCapabilityDTO;
import cn.jia.agent.entity.AbilityCompareRequestDTO;
import cn.jia.agent.entity.AbilityEvaluationRequestDTO;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentPersonaBindRequestDTO;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRosterSearchDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingCancelDTO;
import cn.jia.agent.entity.funding.AgentTaskClaimRequestDTO;
import cn.jia.agent.entity.funding.AgentTaskQuoteRequestDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskNoteDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskRecommendationDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.DialogueRequestDTO;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.agent.service.HostingRentAdmissionException;
import cn.jia.agent.service.funding.FundedBountyActor;
import cn.jia.agent.service.funding.FundedBountyException;
import cn.jia.agent.service.funding.FundedBountyRequestDigest;
import cn.jia.agent.service.funding.FundedBountyQuoteClaimService;
import cn.jia.agent.service.funding.FundedBountyService;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.security.AllowSensitiveOutput;
import jakarta.servlet.http.HttpServletRequest;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentService agentService;
    private final AbilityEvaluationService abilityEvaluationService;
    private final FundedBountyService fundedBountyService;
    private final FundedBountyQuoteClaimService fundedBountyQuoteClaimService;

    /** Backward-compatible constructor for existing unfunded controller tests. */
    public AgentController(AgentService agentService, AbilityEvaluationService abilityEvaluationService) {
        this.agentService = Objects.requireNonNull(agentService, "agentService");
        this.abilityEvaluationService = Objects.requireNonNull(abilityEvaluationService, "abilityEvaluationService");
        this.fundedBountyService = null;
        this.fundedBountyQuoteClaimService = null;
    }

    @Autowired
    public AgentController(AgentService agentService, AbilityEvaluationService abilityEvaluationService,
            ObjectProvider<FundedBountyService> fundedBountyServiceProvider,
            ObjectProvider<FundedBountyQuoteClaimService> fundedBountyQuoteClaimServiceProvider) {
        this(agentService, abilityEvaluationService,
                Objects.requireNonNull(fundedBountyServiceProvider, "fundedBountyServiceProvider").getIfAvailable(),
                Objects.requireNonNull(fundedBountyQuoteClaimServiceProvider,
                        "fundedBountyQuoteClaimServiceProvider").getIfAvailable());
    }

    AgentController(AgentService agentService, AbilityEvaluationService abilityEvaluationService,
            FundedBountyService fundedBountyService) {
        this(agentService, abilityEvaluationService, fundedBountyService, null);
    }

    AgentController(AgentService agentService, AbilityEvaluationService abilityEvaluationService,
            FundedBountyService fundedBountyService,
            FundedBountyQuoteClaimService fundedBountyQuoteClaimService) {
        this.agentService = Objects.requireNonNull(agentService, "agentService");
        this.abilityEvaluationService = Objects.requireNonNull(abilityEvaluationService, "abilityEvaluationService");
        this.fundedBountyService = fundedBountyService;
        this.fundedBountyQuoteClaimService = fundedBountyQuoteClaimService;
    }

    @PostMapping("/register")
    public Object register(@RequestBody AgentRegisterDTO request) {
        return JsonResult.success(agentService.register(request));
    }

    @GetMapping("/list")
    public Object list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String ability,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {
        return page(agentService.list(status, ability, pageNum, pageSize));
    }

    @GetMapping("/map")
    public Object mapAgents() {
        return JsonResult.success(agentService.listMapAgents());
    }

    @GetMapping("/capabilities")
    public Object capabilities() {
        List<AgentCapabilityDTO> capabilities = agentService.listCapabilities();
        return JsonResult.success(capabilities);
    }

    @GetMapping("/personas/catalog")
    public Object personaCatalog() {
        return JsonResult.success(agentService.listPersonaCatalog());
    }

    @PostMapping("/personas/{personaCode}/bind")
    @AllowSensitiveOutput(reason = "persona binding returns the codex-ws-agent API key needed for local setup")
    public Object bindPersona(@PathVariable String personaCode,
            @RequestBody(required = false) AgentPersonaBindRequestDTO request) {
        if (request == null || request.getMode() == null || request.getMode().isBlank()) {
            return JsonResult.success(agentService.bindPersona(personaCode));
        }
        return JsonResult.success(agentService.bindPersona(personaCode, request.getMode()));
    }

    @DeleteMapping("/personas/{personaCode}/bind")
    public Object unbindPersona(@PathVariable String personaCode) {
        agentService.unbindPersona(personaCode);
        return JsonResult.success();
    }

    @PostMapping("/roster")
    public Object roster(@RequestBody AgentRosterSearchDTO request) {
        int pageNum = request.getPageNum() == null ? 1 : request.getPageNum();
        int pageSize = request.getPageSize() == null ? 50 : request.getPageSize();
        return page(agentService.listRoster(request.getStatus(), request.getAbility(), pageNum, pageSize));
    }

    @GetMapping("/{agentId}")
    public Object get(@PathVariable String agentId) {
        return JsonResult.success(agentService.get(agentId));
    }

    @PutMapping("/{agentId}/status")
    public Object updateStatus(@PathVariable String agentId, @RequestBody AgentStatusDTO request) {
        return JsonResult.success(agentService.updateStatus(agentId, request));
    }

    @GetMapping("/{agentId}/tasks")
    public Object getTasks(@PathVariable String agentId) {
        return JsonResult.success(agentService.getAgentTasks(agentId));
    }

    @GetMapping("/personas")
    public Object personas() {
        return JsonResult.success(agentService.listPersonas());
    }

    @GetMapping("/persona/{name}")
    public Object persona(@PathVariable String name) {
        return JsonResult.success(agentService.getPersona(name));
    }

    @PostMapping("/dialogue")
    public Object dialogue(@RequestBody DialogueRequestDTO request) {
        return JsonResult.success(agentService.generateDialogue(request));
    }

    @GetMapping("/stats")
    public Object stats(@RequestParam String agentId) {
        return JsonResult.success(agentService.getStats(agentId));
    }

    @PostMapping("/tasks/search")
    public Object searchTasks(@RequestBody AgentTaskSearchDTO request) {
        return page(agentService.searchTasks(request));
    }

    @PostMapping("/tasks/status-counts")
    public Object countTasksByStatus(@RequestBody AgentTaskSearchDTO request) {
        return JsonResult.success(agentService.countTasksByStatus(request));
    }

    @PostMapping("/tasks")
    public Object createTask(@RequestBody AgentTaskCreateDTO request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        if (!hasFundingFields(request)) {
            return JsonResult.success(agentService.createTask(request));
        }
        FundedBountyActor actor = requireMoneyActor(authentication);
        FundedBountyService service = requireFundedBountyService();
        return JsonResult.success(service.create(actor, idempotencyKey,
                FundedBountyRequestDigest.create(request), request));
    }

    @GetMapping("/tasks/{taskId}")
    public Object getTask(@PathVariable String taskId) {
        return JsonResult.success(agentService.getTask(taskId));
    }

    @PostMapping("/tasks/{taskId}/funding/cancel")
    public Object cancelTaskFunding(@PathVariable String taskId,
            @RequestBody AgentTaskFundingCancelDTO request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        FundedBountyActor actor = requireMoneyActor(authentication);
        FundedBountyService service = requireFundedBountyService();
        String expected = request == null ? null : request.getExpectedTaskVersion();
        long expectedVersion = parseCanonicalVersion(expected);
        return JsonResult.success(service.cancel(actor, idempotencyKey,
                FundedBountyRequestDigest.cancel(taskId, expected), taskId, expectedVersion));
    }

    @PostMapping("/tasks/{taskId}/quotes")
    public Object quoteTask(@PathVariable String taskId,
            @RequestBody AgentTaskQuoteRequestDTO request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        FundedBountyActor actor = requireMoneyActor(authentication);
        FundedBountyQuoteClaimService service = requireFundedBountyQuoteClaimService();
        return JsonResult.success(service.quote(actor, idempotencyKey,
                FundedBountyRequestDigest.quote(taskId, request), taskId, request));
    }

    @PostMapping("/tasks/{taskId}/claim")
    public Object claimTask(@PathVariable String taskId,
            @RequestBody AgentTaskClaimRequestDTO request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        FundedBountyActor actor = requireMoneyActor(authentication);
        FundedBountyQuoteClaimService service = requireFundedBountyQuoteClaimService();
        return JsonResult.success(service.claim(actor, idempotencyKey,
                FundedBountyRequestDigest.claim(taskId, request), taskId, request));
    }

    @PostMapping("/tasks/{taskId}/assign")
    public Object assignTask(@PathVariable String taskId, @RequestBody AgentTaskAssignDTO request) {
        return JsonResult.success(agentService.assignTask(taskId, request));
    }

    @PostMapping("/tasks/{taskId}/recommend")
    public Object recommendTaskAssignees(@PathVariable String taskId) {
        List<AgentTaskRecommendationDTO> recommendations = agentService.recommendTaskAssignees(taskId);
        return JsonResult.success(recommendations);
    }

    @PostMapping("/tasks/{taskId}/auto-assign")
    public Object autoAssignTask(@PathVariable String taskId, @RequestBody(required = false) AgentTaskAssignDTO request) {
        return JsonResult.success(agentService.autoAssignTask(taskId, request == null ? new AgentTaskAssignDTO() : request));
    }

    @PostMapping("/tasks/{taskId}/report")
    public Object reportTask(@PathVariable String taskId, @RequestBody AgentTaskReportDTO request) {
        return JsonResult.success(agentService.reportTask(taskId, request));
    }

    @PostMapping("/tasks/{taskId}/notes")
    public Object addTaskNote(@PathVariable String taskId, @RequestBody AgentTaskNoteDTO request) {
        return JsonResult.success(agentService.addTaskNote(taskId, request));
    }

    @GetMapping("/tasks/{taskId}/notes")
    public Object listTaskNotes(@PathVariable String taskId) {
        return JsonResult.success(agentService.listTaskNotes(taskId));
    }

    @PostMapping("/tasks/{taskId}/archive")
    public Object archiveTask(@PathVariable String taskId) {
        return JsonResult.success(agentService.archiveTask(taskId));
    }

    @PostMapping("/evaluate")
    public Object evaluate(@RequestBody AbilityEvaluationRequestDTO request) {
        return JsonResult.success(abilityEvaluationService.evaluate(request));
    }

    @PostMapping("/compare")
    public Object compare(@RequestBody AbilityCompareRequestDTO request) {
        return JsonResult.success(abilityEvaluationService.compare(request));
    }

    @GetMapping("/evaluation/{agentName}")
    public Object evaluationHistory(@PathVariable String agentName) {
        return JsonResult.success(abilityEvaluationService.history(agentName));
    }

    @GetMapping("/evaluation/latest/{agentName}")
    public Object latestEvaluation(@PathVariable String agentName) {
        return JsonResult.success(abilityEvaluationService.latest(agentName));
    }

    @GetMapping("/evaluation/stats")
    public Object evaluationStats() {
        return JsonResult.success(abilityEvaluationService.stats());
    }

    @DeleteMapping("/evaluation/{id}")
    public Object deleteEvaluation(@PathVariable Long id) {
        abilityEvaluationService.delete(id);
        return JsonResult.success();
    }

    @ExceptionHandler(FundedBountyException.class)
    public ResponseEntity<JsonResult<Void>> handleFundedBountyException(
            FundedBountyException exception, HttpServletRequest request) {
        log.warn("Funded bounty request rejected: uri={}, code={}, retryable={}",
                request.getRequestURI(), exception.code(), exception.retryable());
        JsonResult<Void> result = JsonResult.failure(exception.code(), exception.getMessage());
        result.setStatus(exception.status().value());
        return ResponseEntity.status(exception.status())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(result);
    }

    @ExceptionHandler(HostingRentAdmissionException.class)
    public ResponseEntity<JsonResult<Void>> handleHostingRentUnavailable(
            HostingRentAdmissionException e, HttpServletRequest request) {
        log.warn("Agent hosting rent request rejected: uri={}, code={}",
                request.getRequestURI(), e.code());
        JsonResult<Void> result = JsonResult.failure(e.code(), e.getMessage());
        result.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(result);
    }

    @ExceptionHandler(AgentBizException.class)
    public JsonResult<Void> handleAgentBizException(AgentBizException e, HttpServletRequest request) {
        log.warn("Agent API request rejected: uri={}, code={}, message={}",
                request.getRequestURI(), e.getCode(), e.getMessage());
        JsonResult<Void> result = JsonResult.failure(e.getCode(), e.getMessage());
        result.setStatus(409);
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public JsonResult<Void> handleIllegalArgumentException(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("Agent API request invalid: uri={}, message={}", request.getRequestURI(), e.getMessage());
        JsonResult<Void> result = JsonResult.failure("BAD_REQUEST", e.getMessage());
        result.setStatus(400);
        return result;
    }

    @ExceptionHandler(Exception.class)
    public JsonResult<Void> handleException(Exception e) {
        log.error("Agent API error", e);
        JsonResult<Void> result = JsonResult.failure("AGENT_ERROR", e.getMessage());
        result.setStatus(500);
        return result;
    }

    private FundedBountyService requireFundedBountyService() {
        if (fundedBountyService == null) {
            throw new FundedBountyException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ECONOMY_PREVIEW_DISABLED", "Funded bounty preview is unavailable");
        }
        return fundedBountyService;
    }

    private FundedBountyQuoteClaimService requireFundedBountyQuoteClaimService() {
        if (fundedBountyQuoteClaimService == null) {
            throw new FundedBountyException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ECONOMY_PREVIEW_DISABLED", "Funded bounty quote/claim preview is unavailable");
        }
        return fundedBountyQuoteClaimService;
    }

    private static boolean hasFundingFields(AgentTaskCreateDTO request) {
        return request != null && (request.getGrossBountyAmountMicro() != null
                || request.getSettlementPolicy() != null
                || request.getRequiredSkillRequirements() != null);
    }

    private static FundedBountyActor requireMoneyActor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new FundedBountyException(HttpStatus.UNAUTHORIZED,
                    "ECONOMY_UNAUTHENTICATED", "Authentication is required");
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        String subject = requiredMoneyClaim(claims, "sub", 100);
        if (!exact(authentication.getName(), subject)) {
            throw new FundedBountyException(HttpStatus.FORBIDDEN,
                    "ECONOMY_FORBIDDEN", "Authenticated money scope is incomplete");
        }
        return new FundedBountyActor(requiredMoneyClaim(claims, "jiacn", 50),
                requiredMoneyClaim(claims, "client_id", 50), subject);
    }

    private static String requiredMoneyClaim(Map<String, Object> claims, String name, int maxBytes) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || text.isEmpty() || hasUnpairedSurrogate(text)
                || text.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || !text.equals(text.strip())
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new FundedBountyException(HttpStatus.FORBIDDEN,
                    "ECONOMY_FORBIDDEN", "Authenticated money scope is incomplete");
        }
        return text;
    }

    private static boolean exact(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static long parseCanonicalVersion(String value) {
        if (value == null || value.isEmpty()
                || !(value.equals("0") || value.charAt(0) >= '1' && value.charAt(0) <= '9')
                || value.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw new FundedBountyException(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "expectedTaskVersion must be a canonical unsigned decimal string");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed == Long.MAX_VALUE) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new FundedBountyException(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "expectedTaskVersion is outside the supported range");
        }
    }

    private <T> JsonResultPage<T> page(PageInfo<T> pageInfo) {
        JsonResultPage<T> result = JsonResultPage.success(pageInfo.getList());
        result.setPageNum(pageInfo.getPageNum());
        result.setTotal(pageInfo.getTotal());
        return result;
    }
}
