package cn.jia.agent.service.impl;

import cn.jia.agent.cache.AgentPersonaCatalogCache;
import cn.jia.agent.cache.AgentPersonaCatalogCache.CatalogEntry;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.common.AgentSceneConstants;
import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentCapabilityDTO;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.entity.AgentStatsDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskAssigneeDTO;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskNoteDTO;
import cn.jia.agent.entity.AgentTaskNoteEntity;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskRecommendationDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingDTO;
import cn.jia.agent.entity.DialogueRequestDTO;
import cn.jia.agent.entity.DialogueTemplateEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.mapper.AgentPersonaCatalogBindingRow;
import cn.jia.agent.mapper.AgentTaskSearchRow;
import cn.jia.agent.mapper.AgentTaskStatsRow;
import cn.jia.agent.mapper.AgentTaskStatusCountRow;
import cn.jia.agent.mapper.AgentTaskStatsScope;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentHostedBindingTransaction;
import cn.jia.agent.service.AgentHostedRuntimePublicationWorker;
import cn.jia.agent.service.AgentHostingWorkAdmission;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentScopePublicationCoordinator;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.HostingRentAdmissionService;
import cn.jia.agent.service.funding.FundedBountyLegacyGuard;
import cn.jia.agent.service.funding.FundedBountyService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.service.AgentService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AgentServiceImpl implements AgentService {
    private static final String REGION_BOUNTY_BOARD = "bounty-board";
    private static final String REGION_COUNCIL_TABLE = "council-table";
    private static final String REGION_MAIN_SEAT = "main-seat";
    private static final long SCENE_EXPECTED_ARRIVAL_MILLIS = 20_000L;
    private static final long SCENE_STATE_EXPIRY_MILLIS = 300_000L;
    private static final int TASK_MEMBERSHIP_SNAPSHOT_LIMIT = 500;
    private static final int MAX_RUNTIME_ABILITIES = 128;
    private static final int MAX_RUNTIME_ABILITY_LENGTH = 100;
    private static final Pattern OPAQUE_AGENT_ID = Pattern.compile("agt_[0-9a-f]{32}");
    private static final Set<String> AGENT_RUNTIME_STATUSES = Set.of(
            AgentConstants.STATUS_ONLINE, AgentConstants.STATUS_BUSY,
            AgentConstants.STATUS_OFFLINE, AgentConstants.STATUS_ERROR);
    private static final Set<AgentTaskMemberStatus> TASK_CONVERSATION_WRITABLE_STATUSES = Set.of(
            AgentTaskMemberStatus.ACCEPTED, AgentTaskMemberStatus.WORKING,
            AgentTaskMemberStatus.BLOCKED);
    private static final Set<String> TASK_MEMBER_ROLES = Set.of(
            "coordinator", "worker", "reviewer", "observer");
    private static final Set<String> TASK_ASSIGNMENT_SOURCES = Set.of(
            "manual", "auto", "migration", "legacy");

    private final AgentRuntimeDao agentRuntimeDao;
    private final AgentIdentityService agentIdentityService;
    private final AgentPersonaDao agentPersonaDao;
    private final AgentPersonaBindingDao agentPersonaBindingDao;
    private AgentPersonaCatalogCache personaCatalogCache = new AgentPersonaCatalogCache();
    private final AgentTaskMetaDao agentTaskMetaDao;
    private final AgentTaskMemberDao agentTaskMemberDao;
    private final AgentLegacyTaskCompatibilityService legacyTaskCompatibilityService;
    private final AgentTaskNoteDao agentTaskNoteDao;
    private final DialogueTemplateDao dialogueTemplateDao;
    private final ObjectProvider<AgentEventPublisher> eventPublisherProvider;
    private final ObjectProvider<TaskService> taskServiceProvider;
    private final ObjectProvider<ApiKeyService> apiKeyServiceProvider;
    private final ObjectProvider<AgentSceneService> sceneServiceProvider;
    private final AgentScopePublicationCoordinator scopePublicationCoordinator;
    private final AgentHostedRuntimePublicationWorker runtimePublicationWorker;
    private final AgentSceneFeatureFlags sceneFeatureFlags;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter taskEventWriter;
    private final AgentCommandTransportCapture commandTransportCapture;
    private final HostingRentAdmissionService hostingRentAdmissionService;
    // Direct legacy test construction has no managed leases. Spring requires the real gate bean.
    private AgentHostingWorkAdmission hostingWorkAdmission = (tenant, client, agent) -> { };

    @org.springframework.beans.factory.annotation.Autowired
    public void setHostingWorkAdmission(AgentHostingWorkAdmission admission) {
        this.hostingWorkAdmission = Objects.requireNonNull(admission);
    }

    @Autowired
    public void setPersonaCatalogCache(AgentPersonaCatalogCache personaCatalogCache) {
        this.personaCatalogCache = Objects.requireNonNull(personaCatalogCache, "personaCatalogCache");
    }

    @Override
    public void requireHostingNewWork(String tenantId, String clientId, String canonicalAgentId) {
        hostingWorkAdmission.requireNewWork(tenantId, clientId, canonicalAgentId);
    }

    private cn.jia.agent.skill.SkillAgentVersions skillAgentVersions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSkillAgentVersions(cn.jia.agent.skill.SkillAgentVersions versions) {
        this.skillAgentVersions = versions;
    }
    private void observeSkillLifecycle(AgentRuntimeEntity row) {
        if (skillAgentVersions != null) skillAgentVersions.observe(row);
    }

    private volatile FundedBountyService fundedBountyService;
    private volatile FundedBountyLegacyGuard fundedBountyLegacyGuard =
            FundedBountyLegacyGuard.unconfigured();

    /** Backward-compatible constructor used by existing focused tests with all M3 flags OFF. */
    public AgentServiceImpl(
            AgentRuntimeDao agentRuntimeDao,
            AgentIdentityService agentIdentityService,
            AgentPersonaDao agentPersonaDao,
            AgentPersonaBindingDao agentPersonaBindingDao,
            AgentTaskMetaDao agentTaskMetaDao,
            AgentTaskMemberDao agentTaskMemberDao,
            AgentLegacyTaskCompatibilityService legacyTaskCompatibilityService,
            AgentTaskNoteDao agentTaskNoteDao,
            DialogueTemplateDao dialogueTemplateDao,
            ObjectProvider<AgentEventPublisher> eventPublisherProvider,
            ObjectProvider<TaskService> taskServiceProvider,
            ObjectProvider<ApiKeyService> apiKeyServiceProvider,
            ObjectProvider<AgentSceneService> sceneServiceProvider,
            AgentScopePublicationCoordinator scopePublicationCoordinator,
            AgentSceneFeatureFlags sceneFeatureFlags,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter taskEventWriter) {
        this(agentRuntimeDao, agentIdentityService, agentPersonaDao, agentPersonaBindingDao,
                agentTaskMetaDao, agentTaskMemberDao, legacyTaskCompatibilityService,
                agentTaskNoteDao, dialogueTemplateDao, eventPublisherProvider,
                taskServiceProvider, apiKeyServiceProvider, sceneServiceProvider,
                scopePublicationCoordinator, new AgentHostedRuntimePublicationWorker(agentRuntimeDao),
                sceneFeatureFlags, mutationTransaction, taskEventWriter,
                AgentCommandTransportCapture.disabledForLegacyConstruction());
    }

    public AgentServiceImpl(
            AgentRuntimeDao agentRuntimeDao,
            AgentIdentityService agentIdentityService,
            AgentPersonaDao agentPersonaDao,
            AgentPersonaBindingDao agentPersonaBindingDao,
            AgentTaskMetaDao agentTaskMetaDao,
            AgentTaskMemberDao agentTaskMemberDao,
            AgentLegacyTaskCompatibilityService legacyTaskCompatibilityService,
            AgentTaskNoteDao agentTaskNoteDao,
            DialogueTemplateDao dialogueTemplateDao,
            ObjectProvider<AgentEventPublisher> eventPublisherProvider,
            ObjectProvider<TaskService> taskServiceProvider,
            ObjectProvider<ApiKeyService> apiKeyServiceProvider,
            ObjectProvider<AgentSceneService> sceneServiceProvider,
            AgentScopePublicationCoordinator scopePublicationCoordinator,
            AgentSceneFeatureFlags sceneFeatureFlags,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter taskEventWriter,
            AgentCommandTransportCapture commandTransportCapture) {
        this(agentRuntimeDao, agentIdentityService, agentPersonaDao, agentPersonaBindingDao,
                agentTaskMetaDao, agentTaskMemberDao, legacyTaskCompatibilityService,
                agentTaskNoteDao, dialogueTemplateDao, eventPublisherProvider,
                taskServiceProvider, apiKeyServiceProvider, sceneServiceProvider,
                scopePublicationCoordinator, new AgentHostedRuntimePublicationWorker(agentRuntimeDao),
                sceneFeatureFlags, mutationTransaction, taskEventWriter, commandTransportCapture);
    }

    public AgentServiceImpl(
            AgentRuntimeDao agentRuntimeDao,
            AgentIdentityService agentIdentityService,
            AgentPersonaDao agentPersonaDao,
            AgentPersonaBindingDao agentPersonaBindingDao,
            AgentTaskMetaDao agentTaskMetaDao,
            AgentTaskMemberDao agentTaskMemberDao,
            AgentLegacyTaskCompatibilityService legacyTaskCompatibilityService,
            AgentTaskNoteDao agentTaskNoteDao,
            DialogueTemplateDao dialogueTemplateDao,
            ObjectProvider<AgentEventPublisher> eventPublisherProvider,
            ObjectProvider<TaskService> taskServiceProvider,
            ObjectProvider<ApiKeyService> apiKeyServiceProvider,
            ObjectProvider<AgentSceneService> sceneServiceProvider,
            AgentScopePublicationCoordinator scopePublicationCoordinator,
            AgentHostedRuntimePublicationWorker runtimePublicationWorker,
            AgentSceneFeatureFlags sceneFeatureFlags,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter taskEventWriter,
            AgentCommandTransportCapture commandTransportCapture) {
        this(agentRuntimeDao, agentIdentityService, agentPersonaDao, agentPersonaBindingDao,
                agentTaskMetaDao, agentTaskMemberDao, legacyTaskCompatibilityService,
                agentTaskNoteDao, dialogueTemplateDao, eventPublisherProvider,
                taskServiceProvider, apiKeyServiceProvider, sceneServiceProvider,
                scopePublicationCoordinator, runtimePublicationWorker, sceneFeatureFlags,
                mutationTransaction, taskEventWriter, commandTransportCapture,
                HostingRentAdmissionService.unconfigured());
    }

    @Autowired
    public AgentServiceImpl(
            AgentRuntimeDao agentRuntimeDao,
            AgentIdentityService agentIdentityService,
            AgentPersonaDao agentPersonaDao,
            AgentPersonaBindingDao agentPersonaBindingDao,
            AgentTaskMetaDao agentTaskMetaDao,
            AgentTaskMemberDao agentTaskMemberDao,
            AgentLegacyTaskCompatibilityService legacyTaskCompatibilityService,
            AgentTaskNoteDao agentTaskNoteDao,
            DialogueTemplateDao dialogueTemplateDao,
            ObjectProvider<AgentEventPublisher> eventPublisherProvider,
            ObjectProvider<TaskService> taskServiceProvider,
            ObjectProvider<ApiKeyService> apiKeyServiceProvider,
            ObjectProvider<AgentSceneService> sceneServiceProvider,
            AgentScopePublicationCoordinator scopePublicationCoordinator,
            AgentHostedRuntimePublicationWorker runtimePublicationWorker,
            AgentSceneFeatureFlags sceneFeatureFlags,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter taskEventWriter,
            AgentCommandTransportCapture commandTransportCapture,
            HostingRentAdmissionService hostingRentAdmissionService) {
        this.agentRuntimeDao = agentRuntimeDao;
        this.agentIdentityService = agentIdentityService;
        this.agentPersonaDao = agentPersonaDao;
        this.agentPersonaBindingDao = agentPersonaBindingDao;
        this.agentTaskMetaDao = agentTaskMetaDao;
        this.agentTaskMemberDao = agentTaskMemberDao;
        this.legacyTaskCompatibilityService = legacyTaskCompatibilityService;
        this.agentTaskNoteDao = agentTaskNoteDao;
        this.dialogueTemplateDao = dialogueTemplateDao;
        this.eventPublisherProvider = eventPublisherProvider;
        this.taskServiceProvider = taskServiceProvider;
        this.apiKeyServiceProvider = apiKeyServiceProvider;
        this.sceneServiceProvider = sceneServiceProvider;
        this.scopePublicationCoordinator = scopePublicationCoordinator;
        this.runtimePublicationWorker = runtimePublicationWorker;
        this.sceneFeatureFlags = sceneFeatureFlags;
        this.mutationTransaction = mutationTransaction;
        this.taskEventWriter = taskEventWriter;
        this.commandTransportCapture = commandTransportCapture;
        this.hostingRentAdmissionService = Objects.requireNonNull(
                hostingRentAdmissionService, "hostingRentAdmissionService");
    }

    @Autowired
    void configureFundedBountyService(ObjectProvider<FundedBountyService> provider) {
        this.fundedBountyService = Objects.requireNonNull(provider, "provider").getIfAvailable();
    }

    @Autowired
    void configureFundedBountyLegacyGuard(ObjectProvider<FundedBountyLegacyGuard> provider) {
        this.fundedBountyLegacyGuard = Objects.requireNonNull(provider, "provider")
                .getIfAvailable(FundedBountyLegacyGuard::failClosed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRegisterResultDTO register(AgentRegisterDTO request) {
        require(!StringUtil.isBlank(request.getAgentId()), "agentId is required");
        if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(request.getAgentId())) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "System agent cannot register externally");
        }

        String clientId = resolveCurrentClientId();
        String jiacn = resolveCurrentJiacn();
        AgentIdentityRegistryEntity identity = agentIdentityService.requireRegistrationIdentityInScope(
                jiacn, clientId, jiacn, request.getAgentId());
        AgentPersonaBindingEntity binding = agentIdentityService.requireActiveBinding(
                identity, Objects.equals(request.getAgentId(), identity.getCanonicalAgentId())
                        ? null : request.getAgentId());
        AgentPersonaEntity persona = requirePersona(binding.getPersonaCode());
        identity = agentIdentityService.activateForFirstRegistration(identity);
        String canonicalAgentId = identity.getCanonicalAgentId();

        String token = UUID.randomUUID().toString().replace("-", "");
        AgentRuntimeEntity entity = Optional.ofNullable(agentRuntimeDao.findByAgentIdForUpdate(canonicalAgentId))
                .map(existing -> requireExactRuntime(existing, canonicalAgentId, clientId, jiacn, binding.getId()))
                .orElseGet(AgentRuntimeEntity::new);
        entity.setAgentId(canonicalAgentId);
        entity.setName(persona.getName());
        entity.setAvatar(StringUtil.isBlank(request.getAvatar()) ? persona.getAvatar() : request.getAvatar());
        entity.setPersonaCode(persona.getPersonaCode());
        entity.setPersonaName(persona.getName());
        entity.setOwnerJiacn(jiacn);
        entity.setBindingId(binding.getId());
        entity.setClientId(clientId);
        entity.setAbilities(resolveRuntimeAbilities(request.getAbilities(), entity.getAbilities(), persona.getAbilities()));
        entity.setEndpoint(request.getEndpoint());
        entity.setTokenHash(token);
        entity.setStatus(AgentConstants.STATUS_ONLINE);
        entity.setLastSeenAt(System.currentTimeMillis());
        entity.setErrorMessage(null);

        if (entity.getId() == null) {
            agentRuntimeDao.insert(entity);
            observeSkillLifecycle(entity);
        } else {
            agentRuntimeDao.updateById(entity);
            observeSkillLifecycle(entity);
        }
        publishAgentSnapshotAfterCommit("agent-register", jiacn, clientId, jiacn,
                entity.getAgentId(), requireBindingId(entity));
        return new AgentRegisterResultDTO(entity.getAgentId(), token, entity.getStatus());
    }

    @Override
    public PageInfo<AgentRuntimeDTO> list(String status, String ability, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<AgentRuntimeDTO> agents = agentRuntimeDao.findByStatusAndAbility(status, ability)
                .stream()
                .map(this::toRuntimeDTO)
                .toList();
        return PageInfo.of(agents);
    }

    @Override
    public PageInfo<AgentRuntimeDTO> listRoster(String status, String ability, int pageNum, int pageSize) {
        String clientId = resolveCurrentClientId();
        String ownerJiacn = resolveCurrentJiacn();
        PageHelper.startPage(pageNum, pageSize);
        PageInfo<AgentRuntimeEntity> agents = PageInfo.of(
                agentRuntimeDao.findRosterByOwner(clientId, ownerJiacn, status, ability));
        agents.getList().forEach(runtime -> requireBatchRuntimeScope(
                runtime, clientId, ownerJiacn));
        RuntimeBatchEnrichment enrichment = loadRuntimeBatchEnrichment(agents.getList(), false);
        return agents.convert(runtime -> toRuntimeDTO(
                runtime, clientId, ownerJiacn, enrichment));
    }

    @Override
    public List<AgentRuntimeDTO> listMapAgents() {
        String clientId = resolveCurrentClientId();
        String ownerJiacn = resolveCurrentJiacn();
        List<AgentRuntimeEntity> visible = Optional.ofNullable(agentRuntimeDao.findMapVisible(clientId))
                .orElseGet(Collections::emptyList);
        visible.forEach(runtime -> requireBatchRuntimeScope(runtime, clientId, null));
        RuntimeBatchEnrichment enrichment = loadRuntimeBatchEnrichment(visible, true);
        List<AgentRuntimeDTO> agents = new ArrayList<>(visible.stream()
                .map(runtime -> toRuntimeDTO(runtime, clientId, ownerJiacn, enrichment))
                .toList());
        agents.add(buildSongjiangDTO(enrichment.personaByCode(
                AgentConstants.BUILTIN_SONGJIANG_PERSONA_CODE)));
        return agents;
    }

    @Override
    public List<AgentCapabilityDTO> listCapabilities() {
        return listCapabilities(resolveCurrentClientId(), resolveCurrentJiacn());
    }

    private List<AgentCapabilityDTO> listCapabilities(String clientId, String ownerJiacn) {
        List<AgentRuntimeEntity> roster = Optional.ofNullable(agentRuntimeDao
                .findRosterByOwner(clientId, ownerJiacn, null, null))
                .orElseGet(Collections::emptyList);
        List<AgentCapabilityDTO> capabilities = new ArrayList<>(roster
                .stream()
                .map(agent -> toCapabilityDTO(toRuntimeDTO(agent)))
                .toList());
        capabilities.add(toCapabilityDTO(buildSongjiangDTO()));
        return capabilities;
    }

    @Override
    public List<AgentRuntimeDTO> listPersonaCatalog(
            String tenantId, String clientId, String ownerJiacn) {
        AgentHostedBindingTransaction.Scope scope =
                new AgentHostedBindingTransaction.Scope(tenantId, clientId, ownerJiacn);
        List<CatalogEntry> catalog = personaCatalogCache.get(
                scope.tenantId(), scope.clientId(),
                () -> agentPersonaDao.findCatalogProjection(scope.tenantId(), scope.clientId()))
                .entries();
        Map<String, AgentPersonaCatalogBindingRow> bindings = loadCatalogBindings(scope, catalog);
        return catalog.stream()
                .map(persona -> toCatalogDTO(persona, bindings.get(persona.personaCode()), scope))
                .toList();
    }

    private Map<String, AgentPersonaCatalogBindingRow> loadCatalogBindings(
            AgentHostedBindingTransaction.Scope scope, List<CatalogEntry> catalog) {
        List<AgentPersonaCatalogBindingRow> rows = Optional.ofNullable(
                agentPersonaBindingDao.findCatalogOverlay(
                        scope.tenantId(), scope.clientId(), scope.ownerJiacn()))
                .orElseThrow(() -> personaCatalogForbidden(
                        "Persona catalog binding projection is unavailable"));
        Set<String> catalogCodes = catalog.stream()
                .map(CatalogEntry::personaCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (rows.size() > catalogCodes.size()) {
            throw personaCatalogForbidden("Persona catalog binding projection is unbounded");
        }
        LinkedHashMap<String, AgentPersonaCatalogBindingRow> bindings = new LinkedHashMap<>();
        for (AgentPersonaCatalogBindingRow row : rows) {
            requireCatalogOverlay(scope, row);
            if (!catalogCodes.contains(row.getPersonaCode())) {
                throw personaCatalogForbidden(
                        "Persona catalog binding references unavailable metadata");
            }
            if (bindings.putIfAbsent(row.getPersonaCode(), row) != null) {
                throw personaCatalogForbidden(
                        "Persona catalog returned duplicate active owner bindings");
            }
        }
        return Map.copyOf(bindings);
    }

    private void requireCatalogOverlay(
            AgentHostedBindingTransaction.Scope scope, AgentPersonaCatalogBindingRow row) {
        if (row == null || row.getBindingId() == null || row.getBindingId() <= 0
                || row.getBindingStatus() == null
                || row.getBindingStatus() != AgentConstants.BINDING_STATUS_ACTIVE
                || !Objects.equals(scope.tenantId(), row.getBindingTenantId())
                || !Objects.equals(scope.clientId(), row.getBindingClientId())
                || !Objects.equals(scope.ownerJiacn(), row.getBindingOwnerJiacn())
                || !isExactPersonaCatalogText(row.getPersonaCode(), 50)
                || !isExactPersonaCatalogText(row.getBindingAgentId(), 100)) {
            throw personaCatalogForbidden(
                    "Persona catalog binding escaped the byte-exact requested scope");
        }
        if (row.getIdentityId() == null || row.getIdentityId() <= 0
                || !Objects.equals(row.getBindingId(), row.getIdentityBindingId())
                || !Objects.equals(scope.tenantId(), row.getIdentityTenantId())
                || !Objects.equals(scope.clientId(), row.getIdentityClientId())
                || !Objects.equals(scope.ownerJiacn(), row.getIdentityOwnerJiacn())
                || !isExactPersonaCatalogText(row.getCanonicalAgentId(), 100)
                || !validCatalogCanonicalIdentity(row)
                || !Boolean.TRUE.equals(row.getAgentReferenceValid())) {
            throw personaCatalogForbidden(
                    "Persona catalog identity escaped the byte-exact requested scope");
        }
        boolean runtimeAbsent = row.getRuntimeId() == null;
        if (runtimeAbsent) {
            if (row.getRuntimeTenantId() != null || row.getRuntimeClientId() != null
                    || row.getRuntimeOwnerJiacn() != null || row.getRuntimeBindingId() != null
                    || row.getRuntimeAgentId() != null || row.getRuntimeAbilities() != null
                    || row.getRuntimeStatus() != null) {
                throw personaCatalogForbidden("Persona catalog runtime projection is incomplete");
            }
        } else if (row.getRuntimeId() <= 0
                || !Objects.equals(scope.tenantId(), row.getRuntimeTenantId())
                || !Objects.equals(scope.clientId(), row.getRuntimeClientId())
                || !Objects.equals(scope.ownerJiacn(), row.getRuntimeOwnerJiacn())
                || !Objects.equals(row.getBindingId(), row.getRuntimeBindingId())
                || !Objects.equals(row.getCanonicalAgentId(), row.getRuntimeAgentId())
                || row.getRuntimeStatus() == null
                || !AGENT_RUNTIME_STATUSES.contains(row.getRuntimeStatus())) {
            throw personaCatalogForbidden(
                    "Persona catalog runtime escaped the byte-exact requested scope");
        }
    }

    private boolean validCatalogCanonicalIdentity(AgentPersonaCatalogBindingRow row) {
        String canonical = row.getCanonicalAgentId();
        boolean lifecycleValid = AgentConstants.IDENTITY_STATUS_PROVISIONED.equals(row.getLifecycleStatus())
                || AgentConstants.IDENTITY_STATUS_ACTIVE.equals(row.getLifecycleStatus());
        if (!lifecycleValid) {
            return false;
        }
        if (AgentConstants.IDENTITY_TYPE_OPAQUE.equals(row.getCanonicalType())) {
            return OPAQUE_AGENT_ID.matcher(canonical).matches();
        }
        return AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL.equals(row.getCanonicalType())
                && !OPAQUE_AGENT_ID.matcher(canonical).matches()
                && !AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(canonical);
    }

    private boolean isExactPersonaCatalogText(String value, int maxCodePoints) {
        if (value == null || value.isEmpty()
                || value.codePointCount(0, value.length()) > maxCodePoints
                || isPersonaCatalogPadding(value.codePointAt(0))
                || isPersonaCatalogPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPersonaCatalogPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private AgentBizException personaCatalogForbidden(String message) {
        return new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRuntimeDTO bindPersona(String tenantId, String clientId, String jiacn, String personaCode) {
        AgentHostedBindingTransaction.Scope exactScope =
                new AgentHostedBindingTransaction.Scope(tenantId, clientId, jiacn);
        AgentPersonaEntity persona = requirePersona(personaCode);
        if (Boolean.TRUE.equals(persona.getSystemAgent())) {
            throw new AgentBizException(AgentErrorConstants.PERSONA_NOT_BINDABLE, "System persona cannot be bound");
        }
        AgentPersonaBindingEntity bound = agentPersonaBindingDao.findExactActiveByScopeAndPersonaForUpdate(
                exactScope.tenantId(), exactScope.clientId(), exactScope.ownerJiacn(), persona.getPersonaCode());
        if (bound != null) {
            AgentIdentityRegistryEntity identity = agentIdentityService.requireRegistrationIdentityInScope(
                    exactScope.tenantId(), exactScope.clientId(), exactScope.ownerJiacn(), bound.getAgentId());
            agentIdentityService.requireActiveBinding(identity, null);
            AgentRuntimeEntity existing = agentRuntimeDao.findByAgentId(identity.getCanonicalAgentId());
            return existing == null
                    ? createRuntimeFromBinding(bound, identity.getCanonicalAgentId(), persona, AgentConstants.STATUS_OFFLINE)
                    : toRuntimeDTO(requireExactRuntime(existing, identity.getCanonicalAgentId(),
                            exactScope.clientId(), exactScope.ownerJiacn(), bound.getId()));
        }

        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setClientId(exactScope.clientId());
        binding.setTenantId(exactScope.tenantId());
        binding.setJiacn(exactScope.ownerJiacn());
        binding.setPersonaCode(persona.getPersonaCode());
        binding.setAgentId(generateAgentId());
        binding.setBoundAt(System.currentTimeMillis());
        binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        agentPersonaBindingDao.insert(binding);
        AgentIdentityRegistryEntity identity = agentIdentityService.provisionOpaqueIdentity(
                binding, "A08 persona bind: " + persona.getPersonaCode());
        return createRuntimeFromBinding(binding, identity.getCanonicalAgentId(),
                persona, AgentConstants.STATUS_OFFLINE);
    }

    @Override
    public AgentRuntimeDTO requireApiKeyOwnedAgent(String clientId, String jiacn, String agentId) {
        return requireApiKeyOwnedAgent(clientId, jiacn, agentId, false);
    }

    @Override
    public AgentRuntimeDTO requireApiKeyOwnedAgentForUpdate(
            String clientId, String jiacn, String agentId) {
        return requireApiKeyOwnedAgent(clientId, jiacn, agentId, true);
    }

    private AgentRuntimeDTO requireApiKeyOwnedAgent(
            String clientId, String jiacn, String agentId, boolean forUpdate) {
        requireCanonicalIdentityInput(clientId, "clientId");
        requireCanonicalIdentityInput(jiacn, "jiacn");
        requireCanonicalIdentityInput(agentId, "agentId");
        if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agentId)) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "System agent cannot be registered by external clients");
        }

        if (forUpdate) {
            return toRuntimeDTO(requireOwnedAgentForUpdate(clientId, jiacn, agentId));
        }
        agentIdentityService.requireCanonicalAgentIdInScope(
                jiacn, clientId, jiacn, agentId);

        AgentRuntimeEntity agent = agentRuntimeDao.findByAgentId(agentId);
        if (agent == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "Agent runtime is missing or outside the authenticated scope");
        }
        AgentIdentityRegistryEntity identity = agentIdentityService.requireActiveIdentityForBinding(
                jiacn, clientId, jiacn, requireBindingId(agent), agentId);
        requireExactRuntime(agent, agentId, clientId, jiacn, identity.getBindingId());
        return toRuntimeDTO(agent);
    }

    private AgentRuntimeEntity requireOwnedAgentForUpdate(
            String clientId, String jiacn, String agentId) {
        requireCanonicalIdentityInput(clientId, "clientId");
        requireCanonicalIdentityInput(jiacn, "jiacn");
        requireCanonicalIdentityInput(agentId, "agentId");
        if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agentId)) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "System agent cannot be updated by external clients");
        }
        List<String> lockedAgentIds = agentIdentityService.lockActiveCanonicalAgentIdsInScope(
                jiacn, clientId, jiacn, List.of(agentId));
        if (!List.of(agentId).equals(lockedAgentIds)) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "Agent identity lock did not match the requested Agent");
        }
        AgentRuntimeEntity agent = agentRuntimeDao.findByAgentIdForUpdate(agentId);
        if (agent == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "Agent runtime is missing or outside the authenticated scope");
        }
        AgentIdentityRegistryEntity identity = agentIdentityService.requireActiveIdentityForBinding(
                jiacn, clientId, jiacn, requireBindingId(agent), agentId);
        agentIdentityService.requireActiveBinding(identity, null);
        return requireExactRuntime(agent, agentId, clientId, jiacn, identity.getBindingId());
    }

    private void requireCanonicalIdentityInput(String value, String field) {
        if (StringUtil.isBlank(value) || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    field + " is invalid");
        }
    }

    @Override
    public AgentRuntimeDTO get(String agentId) {
        AgentRuntimeEntity agent = requireAgent(agentId);
        if (!AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agentId)) {
            requireOwnedAgent(agent);
        }
        return toRuntimeDTO(agent);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRuntimeDTO updateStatus(String agentId, AgentStatusDTO request) {
        String clientId = resolveCurrentClientId();
        String jiacn = resolveCurrentJiacn();
        AgentRuntimeEntity entity = requireOwnedAgentForUpdate(clientId, jiacn, agentId);
        if (!StringUtil.isBlank(request.getStatus())) {
            validateAgentStatus(request.getStatus());
            entity.setStatus(request.getStatus());
        }
        entity.setCurrentTaskId(request.getCurrentTaskId());
        entity.setCurrentTaskTitle(request.getCurrentTaskTitle());
        entity.setErrorMessage(request.getErrorMessage());
        if (request.getAbilities() != null) {
            entity.setAbilities(JsonUtil.toJson(normalizeRuntimeAbilities(request.getAbilities())));
        }
        entity.setLastSeenAt(System.currentTimeMillis());
        require(agentRuntimeDao.updateById(entity) == 1, "Agent runtime update failed");
        observeSkillLifecycle(entity);
        AgentRuntimeDTO dto = toRuntimeDTO(entity);
        publishAgentSnapshotAfterCommit("agent-presence", jiacn, clientId, jiacn,
                entity.getAgentId(), requireBindingId(entity));
        return dto;
    }

    @Override
    public List<AgentTaskDTO> getAgentTasks(String requestedAgentId) {
        String tenantId = resolveCurrentJiacn();
        String clientId = resolveCurrentClientId();
        String agentId = legacyTaskCompatibilityService.resolveAgentId(
                tenantId, clientId, tenantId, requestedAgentId);
        requireOwnedAgent(requireAgent(agentId));
        List<AgentTaskMemberEntity> memberships = Optional.ofNullable(
                agentTaskMemberDao.listByAgent(tenantId, clientId, agentId, null,
                        TASK_MEMBERSHIP_SNAPSHOT_LIMIT))
                .orElseGet(Collections::emptyList);
        if (!memberships.isEmpty()) {
            require(memberships.size() < TASK_MEMBERSHIP_SNAPSHOT_LIMIT,
                    "Agent task membership snapshot exceeds the safe limit");
            LinkedHashSet<String> taskIds = new LinkedHashSet<>();
            for (AgentTaskMemberEntity member : memberships) {
                require(Objects.equals(tenantId, member.getTenantId())
                                && Objects.equals(clientId, member.getClientId())
                                && Objects.equals(agentId, member.getAgentId())
                                && isExactStoredText(member.getTaskId(), 100),
                        "Persisted Agent task membership is outside the requested scope");
                AgentTaskMemberStatus memberStatus =
                        AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
                if (memberStatus != AgentTaskMemberStatus.REJECTED
                        && memberStatus != AgentTaskMemberStatus.LEFT) {
                    taskIds.add(member.getTaskId());
                }
            }
            return taskIds.stream()
                    .map(taskId -> {
                        AgentTaskMetaEntity task = agentTaskMetaDao.findByTaskId(
                                tenantId, clientId, taskId);
                        require(task != null,
                                "Persisted Agent task membership has no scoped task");
                        requireScopedTaskProjection(task, tenantId, clientId, taskId);
                        return toTaskDTO(task);
                    })
                    .toList();
        }
        List<AgentTaskMetaEntity> legacyTasks = Optional.ofNullable(
                agentTaskMetaDao.findByAgentId(
                        tenantId, clientId, agentId, TASK_MEMBERSHIP_SNAPSHOT_LIMIT))
                .orElseGet(Collections::emptyList);
        require(legacyTasks.size() < TASK_MEMBERSHIP_SNAPSHOT_LIMIT,
                "Legacy Agent task snapshot exceeds the safe limit");
        return legacyTasks.stream()
                .peek(meta -> requireScopedLegacyAgentTaskProjection(
                        meta, tenantId, clientId, agentId))
                .map(this::toTaskDTO)
                .toList();
    }

    @Override
    public List<AgentPersonaEntity> listPersonas() {
        return agentPersonaDao.selectAll();
    }

    @Override
    public AgentPersonaEntity getPersona(String name) {
        AgentPersonaEntity persona = agentPersonaDao.findByName(name);
        if (persona == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_NOT_FOUND, "Agent persona not found");
        }
        return persona;
    }

    @Override
    public String generateDialogue(DialogueRequestDTO request) {
        String personaName = Optional.ofNullable(request.getPersonaName()).orElse("宋江");
        String type = Optional.ofNullable(request.getDialogueType()).orElse("IDLE");
        List<DialogueTemplateEntity> templates = dialogueTemplateDao.findByPersonaAndType(personaName, type);
        publishDiscussionSceneState(personaName, type);
        if (templates.isEmpty()) {
            return "今日聚义厅中，正好议事。";
        }
        return templates.getFirst().getContent();
    }

    @Override
    public AgentRuntimeDTO getStats(String agentId) {
        return get(agentId);
    }

    @Override
    public PageInfo<AgentTaskDTO> searchTasks(AgentTaskSearchDTO request) {
        TaskSearchScope scope = authenticatedTaskSearchScope("search");
        AgentTaskSearchDTO filters = request == null ? new AgentTaskSearchDTO() : request;
        int pageNum = Math.max(Optional.ofNullable(filters.getPageNum()).orElse(1), 1);
        int pageSize = Math.min(Math.max(
                Optional.ofNullable(filters.getPageSize()).orElse(20), 1),
                TASK_MEMBERSHIP_SNAPSHOT_LIMIT);
        long offset = ((long) pageNum - 1L) * pageSize;
        String keyword = StringUtil.isBlank(filters.getKeyword())
                ? null : filters.getKeyword().trim();

        long total = agentTaskMetaDao.countSearch(scope.tenantId(), scope.clientId(),
                filters.getStatus(), filters.getAbility(), keyword);
        require(total >= 0, "Persisted task search total is invalid");
        List<AgentTaskSearchRow> rows = total == 0 ? List.of()
                : Optional.ofNullable(agentTaskMetaDao.searchPage(
                        scope.tenantId(), scope.clientId(), filters.getStatus(),
                        filters.getAbility(), keyword, offset, pageSize))
                        .orElseGet(Collections::emptyList);
        require(rows.size() <= pageSize,
                "Persisted task search page exceeds the requested bound");
        LinkedHashSet<String> exactTaskIds = new LinkedHashSet<>();
        for (AgentTaskSearchRow row : rows) {
            requireScopedTaskProjection(row, scope.tenantId(), scope.clientId(),
                    row == null ? null : row.getTaskId());
            require(isExactStoredText(row.getTaskId(), 100)
                            && exactTaskIds.add(row.getTaskId()),
                    "Persisted task search page contains an invalid or duplicate task ID");
        }

        List<String> taskIds = List.copyOf(exactTaskIds);
        Map<String, List<AgentTaskMemberEntity>> membersByTask = searchMembersByTask(
                scope, taskIds);
        LinkedHashSet<String> agentIds = new LinkedHashSet<>();
        for (AgentTaskSearchRow row : rows) {
            agentIds.addAll(searchAssigneeIds(row, membersByTask.get(row.getTaskId())));
        }
        Map<String, AgentRuntimeEntity> runtimesById = searchRuntimesById(agentIds);
        List<AgentTaskDTO> tasks = rows.stream()
                .map(row -> toSearchTaskDTO(row,
                        searchAssigneeIds(row, membersByTask.get(row.getTaskId())),
                        runtimesById))
                .toList();

        PageInfo<AgentTaskDTO> page = PageInfo.of(tasks);
        page.setPageNum(pageNum);
        page.setPageSize(pageSize);
        page.setTotal(total);
        return page;
    }

    @Override
    public Map<String, Long> countTasksByStatus(AgentTaskSearchDTO request) {
        TaskSearchScope scope = authenticatedTaskSearchScope("count");
        AgentTaskSearchDTO filters = request == null ? new AgentTaskSearchDTO() : request;
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", 0L);
        List.of(AgentConstants.TASK_STATUS_OPEN, AgentConstants.TASK_STATUS_ASSIGNED,
                AgentConstants.TASK_STATUS_RUNNING, AgentConstants.TASK_STATUS_COMPLETED,
                AgentConstants.TASK_STATUS_FAILED, AgentConstants.TASK_STATUS_ARCHIVED)
                .forEach(status -> counts.put(status, 0L));

        List<AgentTaskStatusCountRow> rows = Optional.ofNullable(
                agentTaskMetaDao.countSearchByStatus(scope.tenantId(), scope.clientId(),
                        filters.getAbility(), filters.getKeyword()))
                .orElseGet(Collections::emptyList);
        long total = 0L;
        for (AgentTaskStatusCountRow row : rows) {
            require(row != null
                            && Objects.equals(scope.tenantId(), row.getTenantId())
                            && Objects.equals(scope.clientId(), row.getClientId())
                            && isExactStoredText(row.getStatus(), 20)
                            && row.getTaskCount() != null && row.getTaskCount() >= 0,
                    "Persisted task status count is outside the authenticated scope");
            total = Math.addExact(total, row.getTaskCount());
            counts.put(row.getStatus(), Math.addExact(
                    counts.getOrDefault(row.getStatus(), 0L), row.getTaskCount()));
        }
        counts.put("total", total);
        return counts;
    }

    private TaskSearchScope authenticatedTaskSearchScope(String operation) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "Authenticated task " + operation + " scope is required");
        }
        Object tenantClaim = jwtAuthentication.getToken().getClaims().get("jiacn");
        Object clientClaim = jwtAuthentication.getToken().getClaims().get("client_id");
        if (!(tenantClaim instanceof String tenantId)
                || !(clientClaim instanceof String clientId)
                || !isExactAuthenticatedScopeId(tenantId)
                || !isExactAuthenticatedScopeId(clientId)
                || "0".equals(tenantId) || "0".equals(clientId)) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "Authenticated task " + operation + " scope is invalid");
        }
        return new TaskSearchScope(tenantId, clientId);
    }

    private boolean isExactAuthenticatedScopeId(String value) {
        return value != null && !value.isEmpty()
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.codePointCount(0, value.length()) <= 50
                && !Character.isWhitespace(value.codePointAt(0))
                && !Character.isSpaceChar(value.codePointAt(0))
                && !Character.isWhitespace(value.codePointBefore(value.length()))
                && !Character.isSpaceChar(value.codePointBefore(value.length()))
                && !value.codePoints().allMatch(codePoint ->
                        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private Map<String, List<AgentTaskMemberEntity>> searchMembersByTask(
            TaskSearchScope scope, List<String> taskIds) {
        Map<String, List<AgentTaskMemberEntity>> byTask = new LinkedHashMap<>();
        Set<String> requestedTaskIds = new LinkedHashSet<>(taskIds);
        List<AgentTaskMemberEntity> members = Optional.ofNullable(
                agentTaskMetaDao.findSearchMembers(
                        scope.tenantId(), scope.clientId(), taskIds))
                .orElseGet(Collections::emptyList);
        for (AgentTaskMemberEntity member : members) {
            require(member != null
                            && Objects.equals(scope.tenantId(), member.getTenantId())
                            && Objects.equals(scope.clientId(), member.getClientId())
                            && requestedTaskIds.contains(member.getTaskId())
                            && isExactStoredText(member.getTaskId(), 100)
                            && isExactStoredText(member.getAgentId(), 100),
                    "Persisted task member is outside the authenticated search scope");
            AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
            byTask.computeIfAbsent(member.getTaskId(), ignored -> new ArrayList<>())
                    .add(member);
        }
        return byTask;
    }

    private List<String> searchAssigneeIds(
            AgentTaskSearchRow task, List<AgentTaskMemberEntity> members) {
        if (members == null || members.isEmpty()) {
            return parseAssignedAgentIds(task.getAssignedAgentId());
        }
        LinkedHashSet<String> agentIds = new LinkedHashSet<>();
        for (AgentTaskMemberEntity member : members) {
            AgentTaskMemberStatus status =
                    AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
            if (status != AgentTaskMemberStatus.REJECTED
                    && status != AgentTaskMemberStatus.LEFT) {
                agentIds.add(member.getAgentId());
            }
        }
        return List.copyOf(agentIds);
    }

    private Map<String, AgentRuntimeEntity> searchRuntimesById(Set<String> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, AgentRuntimeEntity> byId = new LinkedHashMap<>();
        List<AgentRuntimeEntity> runtimes = Optional.ofNullable(
                agentTaskMetaDao.findSearchRuntimes(new ArrayList<>(agentIds)))
                .orElseGet(Collections::emptyList);
        for (AgentRuntimeEntity runtime : runtimes) {
            require(runtime != null && agentIds.contains(runtime.getAgentId())
                            && isExactStoredText(runtime.getAgentId(), 100)
                            && !byId.containsKey(runtime.getAgentId()),
                    "Persisted Agent runtime is outside the task search projection");
            byId.put(runtime.getAgentId(), runtime);
        }
        return byId;
    }

    private record TaskSearchScope(String tenantId, String clientId) {
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskDTO createTask(AgentTaskCreateDTO request) {
        require(request != null && !StringUtil.isBlank(request.getTitle()), "title is required");
        require(request.getGrossBountyAmountMicro() == null && request.getSettlementPolicy() == null
                        && request.getRequiredSkillRequirements() == null,
                "funded task fields require the funded bounty endpoint");

        String tenantId = resolveCurrentJiacn();
        String clientId = resolveCurrentClientId();
        String reservedTaskId = UUID.randomUUID().toString();
        AgentTaskMetaEntity reservedMeta = new AgentTaskMetaEntity();
        reservedMeta.setTaskId(reservedTaskId);
        reservedMeta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        reservedMeta.setRequiredAbilities(JsonUtil.toJson(
                Optional.ofNullable(request.getRequiredAbilities()).orElseGet(Collections::emptyList)));
        reservedMeta.setReward(request.getReward());
        reservedMeta.setCollaborationMode("single");
        reservedMeta.setRiskLevel("low");
        reservedMeta.setMaxAgents(1);
        reservedMeta.setReviewRequired(false);
        reservedMeta.setTaskVersion(0L);
        reservedMeta.setCurrentEventVersion(0L);
        applyCurrentTaskScope(reservedMeta);

        return mutationTransaction.executeAfterTaskRootReservation(
                tenantId, clientId, reservedTaskId,
                () -> agentTaskMetaDao.insert(reservedMeta),
                (reservedRoot, rootCreated) -> {
                    requireScopedTaskProjection(
                            reservedRoot, tenantId, clientId, reservedTaskId);
                    if (!rootCreated) {
                        return taskCreateResult(reservedRoot, request);
                    }

                    String finalTaskId = reservedTaskId;
                    TaskService taskService = taskServiceProvider.getIfAvailable();
                    if (taskService != null) {
                        TaskPlanEntity taskPlan = taskPlanFor(request);
                        TaskPlanEntity persistedPlan = taskService.create(taskPlan);
                        Long planId = taskPlan.getId() != null
                                ? taskPlan.getId()
                                : persistedPlan == null ? null : persistedPlan.getId();
                        if (planId != null) {
                            finalTaskId = String.valueOf(planId);
                        }
                    }

                    AgentTaskMetaEntity taskRoot = reservedRoot;
                    if (!reservedTaskId.equals(finalTaskId)) {
                        AgentTaskMetaEntity existing = agentTaskMetaDao.findByTaskIdForUpdate(
                                tenantId, clientId, finalTaskId);
                        if (existing != null) {
                            requireScopedTaskProjection(existing, tenantId, clientId, finalTaskId);
                            throw new IllegalStateException(
                                    "Task plan ID collides with an existing scoped task root");
                        }
                        long rekeyedAt = System.currentTimeMillis();
                        require(agentTaskMetaDao.rekeyReservedTaskRoot(
                                        tenantId, clientId, reservedTaskId,
                                        finalTaskId, rekeyedAt) == 1,
                                "Reserved task root planId backfill failed");
                        reservedRoot.setTaskId(finalTaskId);
                        reservedRoot.setUpdateTime(rekeyedAt);
                    }

                    AgentTaskDTO task = taskCreateResult(taskRoot, request);
                    appendTaskCreatedEvent(tenantId, clientId, taskRoot);
                    publishOptionalAfterCommit(
                            "task-create", () -> publishTaskEvent("task_created", task));
                    return task;
                });
    }

    private TaskPlanEntity taskPlanFor(AgentTaskCreateDTO request) {
        TaskPlanEntity taskPlan = new TaskPlanEntity();
        taskPlan.setName(limitLength(request.getTitle(), 30));
        taskPlan.setDescription(limitLength(request.getDescription(), 200));
        taskPlan.setJiacn(resolveCurrentJiacn());
        taskPlan.setPeriod(TaskConstants.TASK_PERIOD_ALLTIME);
        taskPlan.setType(TaskConstants.TASK_TYPE_NOTIFY);
        taskPlan.setStatus(TaskConstants.TASK_STATUS_ENABLE);
        taskPlan.setRemind(TaskConstants.TASK_REMIND_NO);
        if (request.getReward() != null) {
            taskPlan.setAmount(BigDecimal.valueOf(request.getReward()));
        }
        return taskPlan;
    }

    private AgentTaskDTO taskCreateResult(
            AgentTaskMetaEntity taskRoot, AgentTaskCreateDTO request) {
        AgentTaskDTO task = toTaskDTO(taskRoot);
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        return task;
    }

    @Override
    public AgentTaskDTO getTask(String taskId) {
        return toTaskDTO(requireTask(taskId));
    }

    @Override
    public List<String> listTaskMemberAgentIds(String tenantId, String clientId, String taskId) {
        require(!StringUtil.isBlank(tenantId), "tenantId is required");
        require(!StringUtil.isBlank(clientId), "clientId is required");
        require(!StringUtil.isBlank(taskId), "taskId is required");

        List<AgentTaskMemberEntity> members = Optional.ofNullable(
                agentTaskMemberDao.listByTask(tenantId, clientId, taskId)).orElseGet(Collections::emptyList);
        if (!members.isEmpty()) {
            LinkedHashSet<String> agentIds = new LinkedHashSet<>();
            for (AgentTaskMemberEntity member : members) {
                require(Objects.equals(tenantId, member.getTenantId())
                                && Objects.equals(clientId, member.getClientId())
                                && Objects.equals(taskId, member.getTaskId())
                                && isExactStoredText(member.getAgentId(), 100),
                        "Persisted task member is outside the requested scope");
                AgentTaskMemberStatus status =
                        AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
                if (status != AgentTaskMemberStatus.REJECTED
                        && status != AgentTaskMemberStatus.LEFT) {
                    agentIds.add(member.getAgentId());
                }
            }
            return List.copyOf(agentIds);
        }

        AgentTaskMetaEntity legacyMeta = agentTaskMetaDao.findByTaskId(tenantId, clientId, taskId);
        return legacyMeta == null
                ? List.of()
                : parseAssignedAgentIds(legacyMeta.getAssignedAgentId());
    }

    @Override
    public List<String> listTaskWritableMemberAgentIds(
            String tenantId, String clientId, String taskId) {
        require(!StringUtil.isBlank(tenantId), "tenantId is required");
        require(!StringUtil.isBlank(clientId), "clientId is required");
        require(!StringUtil.isBlank(taskId), "taskId is required");

        AgentTaskMetaEntity task = agentTaskMetaDao.findByTaskId(tenantId, clientId, taskId);
        if (task == null || !Objects.equals(tenantId, task.getTenantId())
                || !Objects.equals(clientId, task.getClientId())
                || !Objects.equals(taskId, task.getTaskId())) {
            return List.of();
        }
        try {
            AgentTaskStatus.fromPersistedValue(task.getRewardStatus());
        } catch (IllegalArgumentException invalidTaskStatus) {
            return List.of();
        }

        List<AgentTaskMemberEntity> members = Optional.ofNullable(
                agentTaskMemberDao.listByTask(tenantId, clientId, taskId))
                .orElseGet(Collections::emptyList);
        LinkedHashSet<String> writableAgentIds = new LinkedHashSet<>();
        for (AgentTaskMemberEntity member : members) {
            require(member != null
                            && Objects.equals(tenantId, member.getTenantId())
                            && Objects.equals(clientId, member.getClientId())
                            && Objects.equals(taskId, member.getTaskId())
                            && isExactStoredText(member.getAgentId(), 100)
                            && TASK_MEMBER_ROLES.contains(member.getMemberRole())
                            && TASK_ASSIGNMENT_SOURCES.contains(member.getAssignmentSource()),
                    "Persisted task member is outside the writable task scope");
            AgentTaskMemberStatus status =
                    AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
            if (TASK_CONVERSATION_WRITABLE_STATUSES.contains(status)) {
                writableAgentIds.add(member.getAgentId());
            }
        }
        return List.copyOf(writableAgentIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskDTO assignTask(String taskId, AgentTaskAssignDTO request) {
        return assignTaskInternal(taskId, request, false);
    }

    private AgentTaskDTO assignTaskInternal(
            String taskId, AgentTaskAssignDTO request, boolean automatic) {
        List<String> requestedAgentIds = normalizeAssignAgentIds(request);
        require(!requestedAgentIds.isEmpty(), "agentId is required");
        String tenantId = resolveCurrentJiacn();
        String clientId = resolveCurrentClientId();
        requireLegacyAssignmentAllowed(tenantId, clientId, taskId, automatic, requestedAgentIds.size());
        List<String> agentIds = legacyTaskCompatibilityService.resolveAgentIds(
                tenantId, clientId, tenantId, requestedAgentIds);
        boolean allowQueue = Boolean.TRUE.equals(request.getAllowQueue());

        AgentTaskMetaEntity meta = agentTaskMetaDao.findByTaskId(tenantId, clientId, taskId);
        if (meta == null) {
            meta = new AgentTaskMetaEntity();
            meta.setTaskId(taskId);
            meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
            meta.setTaskVersion(0L);
            meta.setCurrentEventVersion(0L);
        }
        require(taskId.equals(meta.getTaskId()), "taskId does not match current scope");
        validateLegacyAssignableTask(meta);
        AtomicReference<List<AgentRuntimeEntity>> lockedAssignedAgents =
                new AtomicReference<>(List.of());
        AgentLegacyTaskCompatibilityService.AssignOutcome outcome =
                legacyTaskCompatibilityService.assignResolved(
                        tenantId, clientId, taskId, agentIds, automatic,
                        new AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator() {
                            @Override
                            public void beforeIdentityLock(
                                    AgentTaskMetaEntity lockedTask, List<String> canonicalAgentIds) {
                                requireLegacyAssignmentAllowedLocked(tenantId, clientId,
                                        lockedTask.getTaskId(), automatic, canonicalAgentIds.size());
                            }

                            @Override
                            public void validate(
                                    AgentTaskMetaEntity lockedTask, List<String> canonicalAgentIds) {
                                List<AgentRuntimeEntity> runtimes = canonicalAgentIds.stream()
                                        .map(agentId -> lockAssignedRuntime(agentId, tenantId, clientId))
                                        .toList();
                                for (AgentRuntimeEntity agent : runtimes) {
                                    requireHostingNewWork(tenantId, clientId, agent.getAgentId());
                                    validateAssignableAgent(agent, allowQueue);
                                    validateAbility(agent, lockedTask);
                                }
                                if (automatic) {
                                    validateCompleteAbilityCoverage(runtimes, lockedTask);
                                }
                                lockedAssignedAgents.set(List.copyOf(runtimes));
                            }
                        });
        AgentTaskMetaEntity assignedMeta = Optional.ofNullable(
                agentTaskMetaDao.findByTaskId(tenantId, clientId, taskId)).orElseThrow(() ->
                new AgentBizException(AgentErrorConstants.TASK_NOT_FOUND, "Task not found"));
        requireScopedTaskProjection(assignedMeta, tenantId, clientId, taskId);
        List<AgentRuntimeEntity> assignedAgents = lockedAssignedAgents.get();
        if (outcome.changed() && assignedAgents.size() != outcome.agentIds().size()) {
            throw new IllegalStateException("Changed assignment did not validate every target runtime");
        }
        applyAssignmentProjection(assignedMeta, outcome.agentIds());
        AgentTaskDTO task = toTaskDTO(assignedMeta);
        applyTaskAssignees(task, outcome.agentIds());
        if (!outcome.changed()) {
            task.setActionDispatchResults(List.of());
            return task;
        }
        task.setActionDispatchResults(List.of());
        boolean durableAssignmentDelivery = commandTransportCapture.captureTaskInvites(
                task, assignedAgents, outcome.taskAssignedEventId(), outcome.occurredAt());
        publishTaskAssignmentSideEffectsAfterCommit(
                task, assignedAgents, durableAssignmentDelivery);
        publishTaskAssignmentSceneStates(taskId, assignedAgents);
        return task;
    }

    @Override
    public List<AgentTaskRecommendationDTO> recommendTaskAssignees(String taskId) {
        AgentTaskDTO task = getTask(taskId);
        List<AgentRuntimeEntity> candidates = Optional.ofNullable(agentRuntimeDao
                .findRosterByOwner(resolveCurrentClientId(), resolveCurrentJiacn(), null, null))
                .orElseGet(Collections::emptyList)
                .stream()
                .filter(agent -> !AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agent.getAgentId()))
                .filter(agent -> !AgentConstants.STATUS_ERROR.equals(agent.getStatus()))
                .filter(agent -> !AgentConstants.STATUS_OFFLINE.equals(agent.getStatus()))
                .toList();
        return candidates.stream()
                .map(agent -> buildTaskRecommendation(task, agent))
                .filter(recommendation -> recommendation.getAbilityScore() > 0 || task.getRequiredAbilities().isEmpty())
                .sorted((left, right) -> Integer.compare(right.getScore(), left.getScore()))
                .limit(5)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskDTO autoAssignTask(String taskId, AgentTaskAssignDTO request) {
        requireLegacyAssignmentAllowed(resolveCurrentJiacn(), resolveCurrentClientId(), taskId, true, 1);
        AgentTaskDTO task = getTask(taskId);
        List<AgentTaskRecommendationDTO> recommendations = recommendTaskAssignees(taskId);
        List<String> selectedAgentIds = selectAutoAssignAgentIds(task, recommendations);
        require(!selectedAgentIds.isEmpty(), "No available agent can accept this task");

        AgentTaskAssignDTO assignRequest = new AgentTaskAssignDTO();
        assignRequest.setAgentIds(selectedAgentIds);
        assignRequest.setAllowQueue(request != null && Boolean.TRUE.equals(request.getAllowQueue()));
        return assignTaskInternal(taskId, assignRequest, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskDTO reportTask(String taskId, AgentTaskReportDTO request) {
        require(request != null, "report request is required");
        require(!StringUtil.isBlank(request.getAgentId()), "agentId is required");
        String tenantId = resolveCurrentJiacn();
        String clientId = resolveCurrentClientId();
        requireLegacyLifecycleAllowed(tenantId, clientId, taskId);
        String reportingAgentId = legacyTaskCompatibilityService.resolveAgentId(
                tenantId, clientId, tenantId, request.getAgentId());
        requireOwnedAgent(requireAgent(reportingAgentId));

        AgentLegacyTaskCompatibilityService.ReportOutcome outcome =
                legacyTaskCompatibilityService.reportResolved(
                        tenantId, clientId, taskId, reportingAgentId,
                        request.getStatus(), request.getFailureReason());
        AgentTaskMetaEntity meta = Optional.ofNullable(
                agentTaskMetaDao.findByTaskId(tenantId, clientId, taskId)).orElseThrow(() ->
                new AgentBizException(AgentErrorConstants.TASK_NOT_FOUND, "Task not found"));
        requireScopedTaskProjection(meta, tenantId, clientId, taskId);
        require(outcome.taskStatus().equals(meta.getRewardStatus())
                        && Objects.equals(outcome.taskVersion(), meta.getTaskVersion()),
                "task aggregate does not match the compatibility report outcome");

        AgentTaskDTO task = toTaskDTO(meta);
        applyTaskAssignees(task, outcome.memberAgentIds());
        if (!outcome.changed()) {
            return task;
        }

        List<AgentRuntimeDTO> updatedAgents;
        if (outcome.terminalTransition()) {
            updatedAgents = outcome.memberAgentIds().stream()
                    .map(agentId -> updateLegacyReportRuntime(
                            agentId, meta.getTaskId(),
                            terminalRuntimeReportStatus(agentId, outcome.reportingAgentId(),
                                    meta.getRewardStatus()),
                            null, Objects.equals(agentId, outcome.reportingAgentId())
                                    ? request.getFailureReason() : null,
                            true))
                    .toList();
        } else {
            updatedAgents = List.of(updateLegacyReportRuntime(
                    outcome.reportingAgentId(), meta.getTaskId(), request.getStatus(),
                    request.getCurrentTaskTitle(), request.getFailureReason(), false));
        }
        publishLegacyReportSideEffectsAfterCommit(
                "task_" + meta.getRewardStatus(), task, updatedAgents);
        if (outcome.terminalTransition()) {
            publishReturnHomeSceneStates(meta.getTaskId(), updatedAgents);
        }
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskNoteDTO addTaskNote(String taskId, AgentTaskNoteDTO request) {
        require(request != null && !StringUtil.isBlank(request.getContent()),
                "note content is required");
        String noteType = StringUtil.isBlank(request.getNoteType())
                ? "summary" : request.getNoteType();
        TaskEventPayload.ContentDigest digest =
                TaskEventPayload.ContentDigest.fromUtf8(request.getContent());
        // Validate the bounded code before any business row is written.
        TaskEventPayload.builder().put(TaskEventPayload.Key.NOTE_TYPE, noteType);
        String tenantId = resolveCurrentJiacn();
        String clientId = resolveCurrentClientId();
        return mutationTransaction.executeWithLockedTaskRoot(
                tenantId, clientId, taskId, taskRoot -> {
                    requireScopedTaskProjection(taskRoot, tenantId, clientId, taskId);
                    AgentTaskNoteEntity note = new AgentTaskNoteEntity();
                    note.setTaskId(taskId);
                    note.setTenantId(tenantId);
                    note.setClientId(clientId);
                    note.setAuthorId(request.getAuthorId());
                    note.setAuthorType(StringUtil.isBlank(request.getAuthorType())
                            ? "user" : request.getAuthorType());
                    note.setNoteType(noteType);
                    note.setContent(request.getContent());
                    note.setCreatedAt(System.currentTimeMillis());
                    int inserted = agentTaskNoteDao.insert(note);
                    require(inserted == 1 && note.getId() != null, "Task note insert failed");
                    appendTaskNoteEvent(tenantId, clientId, taskRoot, note, digest);
                    return toTaskNoteDTO(note);
                });
    }

    @Override
    public List<AgentTaskNoteDTO> listTaskNotes(String taskId) {
        requireTask(taskId);
        return agentTaskNoteDao.findByTaskId(taskId).stream()
                .map(this::toTaskNoteDTO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskDTO archiveTask(String taskId) {
        String tenantId = resolveCurrentJiacn();
        String clientId = resolveCurrentClientId();
        return mutationTransaction.executeWithLockedTaskRoot(
                tenantId, clientId, taskId, taskRoot -> {
                    requireScopedTaskProjection(taskRoot, tenantId, clientId, taskId);
                    requireLegacyLifecycleAllowedLocked(tenantId, clientId, taskId);
                    AgentTaskStatus currentStatus;
                    try {
                        currentStatus = AgentTaskStatus.fromPersistedValue(
                                taskRoot.getRewardStatus());
                    } catch (IllegalArgumentException invalidStatus) {
                        throw new AgentBizException(AgentErrorConstants.TASK_STATUS_INVALID,
                                "Persisted task status is invalid");
                    }
                    if (currentStatus == AgentTaskStatus.ARCHIVED) {
                        return toTaskDTO(taskRoot);
                    }
                    if (!currentStatus.canTransitionTo(AgentTaskStatus.ARCHIVED)) {
                        throw new AgentBizException(AgentErrorConstants.TASK_STATUS_INVALID,
                                "Task status cannot transition to archived");
                    }
                    Long persistedVersion = taskRoot.getTaskVersion();
                    require(persistedVersion != null && persistedVersion >= 0,
                            "Persisted taskVersion is invalid");
                    if (persistedVersion == Long.MAX_VALUE) {
                        throw new AgentBizException(
                                AgentErrorConstants.TASK_STATUS_INVALID,
                                "Persisted taskVersion cannot be incremented");
                    }
                    long expectedVersion = persistedVersion;
                    if (agentTaskMetaDao.updateStatusByVersion(
                                    tenantId, clientId, taskId, expectedVersion,
                                    AgentConstants.TASK_STATUS_ARCHIVED,
                                    taskRoot.getStartedAt(), taskRoot.getCompletedAt(),
                                    taskRoot.getFailureReason()) != 1) {
                        throw new AgentBizException(
                                AgentErrorConstants.TASK_STATUS_INVALID,
                                "Task archive version conflict");
                    }
                    long resultVersion = expectedVersion + 1;
                    long occurredAt = System.currentTimeMillis();
                    taskRoot.setRewardStatus(AgentConstants.TASK_STATUS_ARCHIVED);
                    taskRoot.setTaskVersion(resultVersion);
                    taskRoot.setUpdateTime(occurredAt);
                    appendTaskArchivedEvent(
                            tenantId, clientId, taskRoot, currentStatus.value(),
                            expectedVersion, occurredAt);
                    AgentTaskDTO task = toTaskDTO(taskRoot);
                    publishOptionalAfterCommit(
                            "task-archive", () -> publishTaskEvent("task_archived", task));
                    publishReturnHomeSceneStatesForAgentIds(
                            taskRoot.getTaskId(), resolveTaskAssigneeIds(taskRoot));
                    return task;
                });
    }

    private void appendTaskCreatedEvent(
            String tenantId, String clientId, AgentTaskMetaEntity task) {
        long occurredAt = positiveEventTime(task.getCreateTime());
        long taskVersion = requireEventTaskVersion(task);
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.TASK_ID, task.getTaskId())
                .put(TaskEventPayload.Key.TASK_TYPE, "agent_task")
                .put(TaskEventPayload.Key.STATUS, task.getRewardStatus())
                .put(TaskEventPayload.Key.RESULT_VERSION, taskVersion)
                .put(TaskEventPayload.Key.CREATED_AT, occurredAt);
        taskEventWriter.append(AgentTaskMutationEventSupport.command(
                tenantId, clientId, task.getTaskId(), TaskEventType.TASK_CREATED,
                TaskEventType.ActorType.SYSTEM, null, TaskEventType.Aggregate.TASK,
                task.getTaskId(), payload, occurredAt, taskVersion));
    }

    private void appendTaskArchivedEvent(
            String tenantId, String clientId, AgentTaskMetaEntity task,
            String fromStatus, long expectedVersion, long occurredAt) {
        long taskVersion = requireEventTaskVersion(task);
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.TASK_ID, task.getTaskId())
                .put(TaskEventPayload.Key.FROM_STATUS, fromStatus)
                .put(TaskEventPayload.Key.TO_STATUS, AgentConstants.TASK_STATUS_ARCHIVED)
                .put(TaskEventPayload.Key.EXPECTED_VERSION, expectedVersion)
                .put(TaskEventPayload.Key.RESULT_VERSION, taskVersion)
                .put(TaskEventPayload.Key.UPDATED_AT, occurredAt);
        taskEventWriter.append(AgentTaskMutationEventSupport.command(
                tenantId, clientId, task.getTaskId(), TaskEventType.TASK_ARCHIVED,
                TaskEventType.ActorType.SYSTEM, null, TaskEventType.Aggregate.TASK,
                task.getTaskId(), payload, occurredAt, taskVersion));
    }

    private void appendTaskNoteEvent(
            String tenantId, String clientId, AgentTaskMetaEntity task,
            AgentTaskNoteEntity note, TaskEventPayload.ContentDigest digest) {
        long occurredAt = positiveEventTime(note.getCreatedAt());
        long taskVersion = requireEventTaskVersion(task);
        String noteId = String.valueOf(note.getId());
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.NOTE_ID, noteId)
                .put(TaskEventPayload.Key.NOTE_TYPE, note.getNoteType())
                .putContentDigest(digest);
        var command = AgentTaskMutationEventSupport.command(
                tenantId, clientId, task.getTaskId(), TaskEventType.PROGRESS_REPORTED,
                TaskEventType.ActorType.SYSTEM, null, TaskEventType.Aggregate.TASK,
                task.getTaskId(), payload, occurredAt, taskVersion);
        String noteSeed = tenantId + '\u0000' + clientId + '\u0000' + task.getTaskId()
                + '\u0000' + TaskEventType.PROGRESS_REPORTED + '\u0000' + noteId;
        command.setEventId("evt_"
                + TaskEventPayload.ContentDigest.fromUtf8(noteSeed).sha256());
        taskEventWriter.append(command);
    }

    private long requireEventTaskVersion(AgentTaskMetaEntity task) {
        if (task.getTaskVersion() == null || task.getTaskVersion() < 0) {
            throw new IllegalArgumentException("Persisted taskVersion is invalid");
        }
        return task.getTaskVersion();
    }

    private long positiveEventTime(Long value) {
        long result = value == null ? System.currentTimeMillis() : value;
        if (result <= 0) {
            throw new IllegalArgumentException("Task event timestamp is invalid");
        }
        return result;
    }

    private void applyCurrentTaskScope(AgentTaskMetaEntity meta) {
        String tenantId = resolveCurrentJiacn();
        String clientId = resolveCurrentClientId();
        if (StringUtil.isBlank(meta.getTenantId())) {
            meta.setTenantId(tenantId);
        } else {
            require(tenantId.equals(meta.getTenantId()), "task tenantId does not match current scope");
        }
        if (StringUtil.isBlank(meta.getClientId())) {
            meta.setClientId(clientId);
        } else {
            require(clientId.equals(meta.getClientId()), "task clientId does not match current scope");
        }
    }

    private AgentTaskMetaEntity requireTask(String taskId) {
        String tenantId = resolveCurrentJiacn();
        String clientId = resolveCurrentClientId();
        AgentTaskMetaEntity task = Optional.ofNullable(
                agentTaskMetaDao.findByTaskId(tenantId, clientId, taskId)).orElseThrow(() ->
                new AgentBizException(AgentErrorConstants.TASK_NOT_FOUND, "Task not found"));
        requireScopedTaskProjection(task, tenantId, clientId, taskId);
        return task;
    }

    private void requireScopedLegacyAgentTaskProjection(
            AgentTaskMetaEntity task, String tenantId, String clientId, String agentId) {
        if (task == null || !Objects.equals(tenantId, task.getTenantId())
                || !Objects.equals(clientId, task.getClientId())
                || !Objects.equals(agentId, task.getAssignedAgentId())
                || !isExactStoredText(task.getTaskId(), 100)) {
            throw new IllegalArgumentException(
                    "Persisted legacy Agent task is outside the byte-exact requested scope");
        }
    }

    private void requireScopedTaskProjection(
            AgentTaskMetaEntity task, String tenantId, String clientId, String taskId) {
        require(task != null
                        && Objects.equals(tenantId, task.getTenantId())
                        && Objects.equals(clientId, task.getClientId())
                        && Objects.equals(taskId, task.getTaskId()),
                "Persisted task does not match the byte-exact current scope");
    }

    private List<String> normalizeAssignAgentIds(AgentTaskAssignDTO request) {
        List<String> agentIds = Optional.ofNullable(request.getAgentIds())
                .orElseGet(Collections::emptyList);
        if (agentIds.isEmpty() && !StringUtil.isBlank(request.getAgentId())) {
            agentIds = List.of(request.getAgentId());
        }
        return new ArrayList<>(new LinkedHashSet<>(agentIds.stream()
                .filter(agentId -> !StringUtil.isBlank(agentId))
                .toList()));
    }

    private boolean isExactStoredText(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private String resolveRuntimeAbilities(List<String> reportedAbilities, String existingAbilities,
            String personaAbilities) {
        if (reportedAbilities != null) {
            return JsonUtil.toJson(normalizeRuntimeAbilities(reportedAbilities));
        }
        if (!StringUtil.isBlank(existingAbilities)) {
            return existingAbilities;
        }
        return StringUtil.isBlank(personaAbilities) ? "[]" : personaAbilities;
    }

    private List<String> normalizeRuntimeAbilities(List<String> abilities) {
        require(abilities.size() <= MAX_RUNTIME_ABILITIES,
                "abilities must contain at most " + MAX_RUNTIME_ABILITIES + " items");
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String rawAbility : abilities) {
            require(rawAbility != null, "ability must not be null");
            String ability = rawAbility.strip();
            require(!ability.isEmpty(), "ability must not be blank");
            require(ability.length() <= MAX_RUNTIME_ABILITY_LENGTH,
                    "ability must contain at most " + MAX_RUNTIME_ABILITY_LENGTH + " characters");
            require(ability.chars().noneMatch(Character::isISOControl),
                    "ability must not contain control characters");
            normalized.putIfAbsent(ability.toLowerCase(Locale.ROOT), ability);
        }
        return new ArrayList<>(normalized.values());
    }

    private void validateCompleteAbilityCoverage(
            List<AgentRuntimeEntity> agents, AgentTaskMetaEntity meta) {
        Set<String> required = parseList(meta.getRequiredAbilities()).stream()
                .map(ability -> ability.toLowerCase(Locale.ROOT))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (required.isEmpty()) {
            return;
        }
        Set<String> available = agents.stream()
                .flatMap(agent -> parseList(agent.getAbilities()).stream())
                .map(ability -> ability.toLowerCase(Locale.ROOT))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (!available.containsAll(required)) {
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(available);
            log.warn("Automatic assignment lost required ability coverage: taskId={}, missingAbilities={}",
                    meta.getTaskId(), missing);
            throw new AgentBizException(AgentErrorConstants.AGENT_ABILITY_MISMATCH,
                    "Automatic assignment does not cover all required abilities");
        }
    }

    private AgentRuntimeEntity lockAssignedRuntime(
            String agentId, String tenantId, String clientId) {
        AgentRuntimeEntity agent = agentRuntimeDao.findByAgentIdForUpdate(agentId);
        if (agent == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_NOT_FOUND, "Agent not found");
        }
        AgentIdentityRegistryEntity identity = agentIdentityService.requireActiveIdentityForBinding(
                tenantId, clientId, tenantId, requireBindingId(agent), agentId);
        agentIdentityService.requireActiveBinding(identity, null);
        return requireExactRuntime(agent, agentId, clientId, tenantId, identity.getBindingId());
    }

    private void validateAssignableAgent(AgentRuntimeEntity agent, boolean allowQueue) {
        if (AgentConstants.STATUS_OFFLINE.equals(agent.getStatus()) && !allowQueue) {
            throw new AgentBizException(AgentErrorConstants.AGENT_OFFLINE, "Agent is offline");
        }
        if (AgentConstants.STATUS_ERROR.equals(agent.getStatus())) {
            throw new AgentBizException(AgentErrorConstants.AGENT_ERROR, "Agent is error");
        }
        if (AgentConstants.STATUS_BUSY.equals(agent.getStatus()) && !allowQueue) {
            throw new AgentBizException(AgentErrorConstants.AGENT_BUSY, "Agent is busy");
        }
    }

    private void validateAbility(AgentRuntimeEntity agent, AgentTaskMetaEntity meta) {
        List<String> required = parseList(meta.getRequiredAbilities());
        if (required.isEmpty()) {
            return;
        }
        Set<String> abilities = parseList(agent.getAbilities()).stream()
                .map(ability -> ability.toLowerCase(Locale.ROOT))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        boolean matched = required.stream()
                .map(ability -> ability.toLowerCase(Locale.ROOT))
                .anyMatch(abilities::contains);
        if (!matched) {
            log.warn("Agent ability mismatch: taskId={}, requiredAbilities={}, agentId={}, agentAbilities={}",
                    meta.getTaskId(), required, agent.getAgentId(), abilities);
            throw new AgentBizException(AgentErrorConstants.AGENT_ABILITY_MISMATCH, "Agent ability mismatch");
        }
    }

    private AgentRuntimeEntity requireAgent(String agentId) {
        AgentRuntimeEntity entity = agentRuntimeDao.findByAgentId(agentId);
        if (entity == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_NOT_FOUND, "Agent not found");
        }
        return entity;
    }

    private AgentPersonaEntity requirePersona(String personaCode) {
        AgentPersonaEntity persona = agentPersonaDao.findByCode(personaCode);
        if (persona == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_NOT_FOUND, "Agent persona not found");
        }
        return persona;
    }

    private void requireOwnedAgent(AgentRuntimeEntity agent) {
        if (agent == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_NOT_FOUND, "Agent not found");
        }
        String clientId = resolveCurrentClientId();
        String jiacn = resolveCurrentJiacn();
        String canonicalAgentId = agentIdentityService.requireCanonicalAgentIdInScope(
                jiacn, clientId, jiacn, agent.getAgentId());
        AgentIdentityRegistryEntity identity = agentIdentityService.requireActiveIdentityForBinding(
                jiacn, clientId, jiacn, requireBindingId(agent), canonicalAgentId);
        agentIdentityService.requireActiveBinding(identity, null);
        requireExactRuntime(agent, canonicalAgentId, clientId, jiacn, identity.getBindingId());
    }

    private AgentRuntimeDTO createRuntimeFromBinding(AgentPersonaBindingEntity binding,
            String canonicalAgentId, AgentPersonaEntity persona, String status) {
        AgentRuntimeEntity runtime = Optional.ofNullable(agentRuntimeDao.findByAgentId(canonicalAgentId))
                .map(existing -> requireExactRuntime(existing, canonicalAgentId,
                        binding.getClientId(), binding.getJiacn(), binding.getId()))
                .orElseGet(AgentRuntimeEntity::new);
        runtime.setAgentId(canonicalAgentId);
        runtime.setName(persona.getName());
        runtime.setAvatar(persona.getAvatar());
        runtime.setPersonaCode(persona.getPersonaCode());
        runtime.setPersonaName(persona.getName());
        runtime.setOwnerJiacn(binding.getJiacn());
        runtime.setBindingId(binding.getId());
        runtime.setClientId(binding.getClientId());
        runtime.setAbilities(persona.getAbilities());
        runtime.setStatus(status);
        runtime.setLastSeenAt(System.currentTimeMillis());
        runtime.setErrorMessage(null);
        if (runtime.getId() == null) {
            agentRuntimeDao.insert(runtime);
            observeSkillLifecycle(runtime);
        } else {
            agentRuntimeDao.updateById(runtime);
            observeSkillLifecycle(runtime);
        }
        return toRuntimeDTO(runtime);
    }

    private String personaDisplayTitle(AgentPersonaEntity persona) {
        return StringUtil.isBlank(persona.getTitle()) ? persona.getName() : persona.getTitle();
    }

    private String safeProfileId(String value) {
        String normalized = safePathName(value);
        return StringUtil.isBlank(normalized) ? "agent" : normalized;
    }

    private String safePathName(String value) {
        String normalized = Optional.ofNullable(value).orElse("").toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        return StringUtil.isBlank(normalized) ? "agent-" + UUID.randomUUID().toString().substring(0, 8) : normalized;
    }

    private AgentRuntimeDTO toRuntimeDTO(AgentRuntimeEntity entity) {
        return toRuntimeDTO(entity, resolveCurrentClientId(), resolveCurrentJiacn());
    }

    private AgentRuntimeDTO toRuntimeDTO(
            AgentRuntimeEntity entity, String clientId, String ownerJiacn) {
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(entity.getAgentId());
        dto.setName(entity.getName());
        dto.setAvatar(entity.getAvatar());
        dto.setOwnerJiacn(entity.getOwnerJiacn());
        dto.setPersonaCode(entity.getPersonaCode());
        dto.setPersonaName(entity.getPersonaName());
        AgentPersonaEntity persona = resolvePersona(entity);
        if (persona != null) {
            dto.setName(StringUtil.isBlank(dto.getName()) ? persona.getName() : dto.getName());
            dto.setAvatar(StringUtil.isBlank(dto.getAvatar()) ? persona.getAvatar() : dto.getAvatar());
            dto.setPersonaCode(persona.getPersonaCode());
            dto.setPersonaName(persona.getName());
            dto.setTitle(persona.getTitle());
            dto.setStarName(persona.getStarName());
            dto.setRankNo(persona.getRankNo());
            dto.setVisualConfig(persona.getVisualConfig());
            dto.setSystemAgent(Boolean.TRUE.equals(persona.getSystemAgent()));
            dto.setAbilities(parseList(StringUtil.isBlank(entity.getAbilities()) ? persona.getAbilities() : entity.getAbilities()));
        } else {
            dto.setSystemAgent(false);
            dto.setAbilities(parseList(entity.getAbilities()));
        }
        dto.setStatus(entity.getStatus());
        dto.setEndpoint(entity.getEndpoint());
        dto.setCurrentTaskId(entity.getCurrentTaskId());
        dto.setCurrentTaskTitle(entity.getCurrentTaskTitle());
        dto.setLastSeenAt(entity.getLastSeenAt());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setBound(!StringUtil.isBlank(entity.getOwnerJiacn()));
        dto.setBoundToMe(Objects.equals(clientId, entity.getClientId())
                && Objects.equals(ownerJiacn, entity.getOwnerJiacn()));
        dto.setCanBind(false);
        dto.setCanOperate(Boolean.TRUE.equals(dto.getBoundToMe()) && !Boolean.TRUE.equals(dto.getSystemAgent()));
        dto.setStats(buildStats(entity));
        return dto;
    }

    private AgentRuntimeDTO toRuntimeDTO(
            AgentRuntimeEntity entity, String clientId, String ownerJiacn,
            RuntimeBatchEnrichment enrichment) {
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(entity.getAgentId());
        dto.setName(entity.getName());
        dto.setAvatar(entity.getAvatar());
        dto.setOwnerJiacn(entity.getOwnerJiacn());
        dto.setPersonaCode(entity.getPersonaCode());
        dto.setPersonaName(entity.getPersonaName());
        AgentPersonaEntity persona = enrichment.resolvePersona(entity);
        if (persona != null) {
            dto.setName(StringUtil.isBlank(dto.getName()) ? persona.getName() : dto.getName());
            dto.setAvatar(StringUtil.isBlank(dto.getAvatar()) ? persona.getAvatar() : dto.getAvatar());
            dto.setPersonaCode(persona.getPersonaCode());
            dto.setPersonaName(persona.getName());
            dto.setTitle(persona.getTitle());
            dto.setStarName(persona.getStarName());
            dto.setRankNo(persona.getRankNo());
            dto.setVisualConfig(persona.getVisualConfig());
            dto.setSystemAgent(Boolean.TRUE.equals(persona.getSystemAgent()));
            dto.setAbilities(parseList(StringUtil.isBlank(entity.getAbilities())
                    ? persona.getAbilities() : entity.getAbilities()));
        } else {
            dto.setSystemAgent(false);
            dto.setAbilities(parseList(entity.getAbilities()));
        }
        dto.setStatus(entity.getStatus());
        dto.setEndpoint(entity.getEndpoint());
        dto.setCurrentTaskId(entity.getCurrentTaskId());
        dto.setCurrentTaskTitle(entity.getCurrentTaskTitle());
        dto.setLastSeenAt(entity.getLastSeenAt());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setBound(!StringUtil.isBlank(entity.getOwnerJiacn()));
        dto.setBoundToMe(Objects.equals(clientId, entity.getClientId())
                && Objects.equals(ownerJiacn, entity.getOwnerJiacn()));
        dto.setCanBind(false);
        dto.setCanOperate(Boolean.TRUE.equals(dto.getBoundToMe())
                && !Boolean.TRUE.equals(dto.getSystemAgent()));
        dto.setStats(buildStats(entity, persona, enrichment.taskStats(entity)));
        return dto;
    }

    private AgentRuntimeDTO toCatalogDTO(CatalogEntry persona,
            AgentPersonaCatalogBindingRow binding, AgentHostedBindingTransaction.Scope scope) {
        if (persona.systemAgent() && binding != null) {
            throw personaCatalogForbidden("System persona must not have a user binding");
        }
        String catalogAgentId = binding == null ? null : binding.getCanonicalAgentId();
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(catalogAgentId);
        dto.setName(persona.name());
        dto.setAvatar(persona.avatar());
        dto.setPersonaCode(persona.personaCode());
        dto.setPersonaName(persona.name());
        dto.setTitle(persona.title());
        dto.setStarName(persona.starName());
        dto.setRankNo(persona.rankNo());
        dto.setVisualConfig(persona.visualConfig());
        dto.setSystemAgent(persona.systemAgent());
        dto.setAbilities(parseList(binding != null && binding.getRuntimeId() != null
                && !StringUtil.isBlank(binding.getRuntimeAbilities())
                ? binding.getRuntimeAbilities() : persona.abilities()));
        dto.setStatus(binding == null || binding.getRuntimeId() == null
                ? AgentConstants.STATUS_OFFLINE : binding.getRuntimeStatus());
        dto.setOwnerJiacn(binding == null ? null : scope.ownerJiacn());
        dto.setBound(binding != null || persona.systemAgent());
        dto.setBoundToMe(binding != null);
        dto.setCanBind(!persona.systemAgent() && binding == null);
        dto.setCanOperate(binding != null);
        AgentStatsDTO stats = new AgentStatsDTO();
        stats.setPower(persona.power());
        stats.setIntelligence(persona.intelligence());
        stats.setLeadership(persona.leadership());
        stats.setCompletedTaskCount(0);
        stats.setFailedTaskCount(0);
        dto.setStats(stats);
        return dto;
    }

    private AgentRuntimeDTO buildSongjiangDTO() {
        return buildSongjiangDTO(agentPersonaDao.findByCode(
                AgentConstants.BUILTIN_SONGJIANG_PERSONA_CODE));
    }

    private AgentRuntimeDTO buildSongjiangDTO(AgentPersonaEntity persona) {
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID);
        dto.setPersonaCode(AgentConstants.BUILTIN_SONGJIANG_PERSONA_CODE);
        dto.setSystemAgent(true);
        dto.setBound(true);
        dto.setBoundToMe(false);
        dto.setCanBind(false);
        dto.setCanOperate(false);
        dto.setStatus(AgentConstants.STATUS_ONLINE);
        dto.setLastSeenAt(System.currentTimeMillis());
        if (persona != null) {
            dto.setName(persona.getName());
            dto.setAvatar(persona.getAvatar());
            dto.setPersonaName(persona.getName());
            dto.setTitle(persona.getTitle());
            dto.setStarName(persona.getStarName());
            dto.setRankNo(persona.getRankNo());
            dto.setVisualConfig(persona.getVisualConfig());
            dto.setAbilities(parseList(persona.getAbilities()));
            AgentRuntimeEntity statsEntity = new AgentRuntimeEntity();
            statsEntity.setAgentId(dto.getAgentId());
            statsEntity.setPersonaCode(persona.getPersonaCode());
            statsEntity.setPersonaName(persona.getName());
            dto.setStats(buildStats(statsEntity, persona, null));
        } else {
            dto.setName("宋江");
            dto.setPersonaName("宋江");
            dto.setTitle("及时雨");
            dto.setAbilities(List.of("coordination", "dispatch", "planning", "briefing", "task_management"));
        }
        return dto;
    }

    private AgentPersonaEntity resolvePersona(AgentRuntimeEntity entity) {
        if (!StringUtil.isBlank(entity.getPersonaCode())) {
            AgentPersonaEntity persona = agentPersonaDao.findByCode(entity.getPersonaCode());
            if (persona != null) {
                return persona;
            }
        }
        return StringUtil.isBlank(entity.getPersonaName()) ? null : agentPersonaDao.findByName(entity.getPersonaName());
    }

    private RuntimeBatchEnrichment loadRuntimeBatchEnrichment(
            List<AgentRuntimeEntity> runtimes, boolean includeSongjiang) {
        if (runtimes.isEmpty() && !includeSongjiang) {
            return RuntimeBatchEnrichment.empty();
        }
        List<AgentPersonaEntity> personas = Optional.ofNullable(
                agentPersonaDao.findRuntimeProjection()).orElseGet(Collections::emptyList);
        LinkedHashMap<String, AgentPersonaEntity> personasByCode = new LinkedHashMap<>();
        LinkedHashMap<String, AgentPersonaEntity> personasByName = new LinkedHashMap<>();
        for (AgentPersonaEntity persona : personas) {
            if (persona == null) {
                throw new IllegalArgumentException("Runtime persona projection contains a null row");
            }
            putUniquePersona(personasByCode, persona.getPersonaCode(), persona, "personaCode");
            putUniquePersona(personasByName, persona.getName(), persona, "personaName");
        }

        LinkedHashMap<RuntimeScopeKey, AgentTaskStatsScope> scopes = new LinkedHashMap<>();
        for (AgentRuntimeEntity runtime : runtimes) {
            RuntimeScopeKey key = RuntimeScopeKey.from(runtime);
            if (key != null) {
                scopes.putIfAbsent(key, new AgentTaskStatsScope(
                        key.tenantId(), key.clientId(), key.agentId()));
            }
        }
        List<AgentTaskStatsRow> rows = scopes.isEmpty()
                ? List.of()
                : Optional.ofNullable(agentTaskMetaDao.findStatsByAgents(
                        new ArrayList<>(scopes.values())))
                        .orElseGet(Collections::emptyList);
        LinkedHashMap<RuntimeScopeKey, AgentTaskStatsRow> statsByScope = new LinkedHashMap<>();
        for (AgentTaskStatsRow row : rows) {
            RuntimeScopeKey key = RuntimeScopeKey.from(row);
            if (!scopes.containsKey(key)) {
                throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                        "Agent task statistics escaped the byte-exact runtime scope");
            }
            validateTaskStatsRow(row);
            if (statsByScope.putIfAbsent(key, row) != null) {
                throw new IllegalArgumentException(
                        "Agent task statistics returned duplicate byte-exact scope rows");
            }
        }
        return new RuntimeBatchEnrichment(
                Map.copyOf(personasByCode), Map.copyOf(personasByName),
                Map.copyOf(statsByScope));
    }

    private void putUniquePersona(Map<String, AgentPersonaEntity> target, String key,
            AgentPersonaEntity persona, String field) {
        if (StringUtil.isBlank(key)) {
            return;
        }
        AgentPersonaEntity previous = target.putIfAbsent(key, persona);
        if (previous != null && previous != persona) {
            throw new IllegalArgumentException(
                    "Runtime persona projection contains duplicate " + field);
        }
    }

    private void requireBatchRuntimeScope(
            AgentRuntimeEntity runtime, String expectedClientId, String expectedOwnerJiacn) {
        boolean valid = runtime != null
                && isExactStoredText(runtime.getAgentId(), 100)
                && isExactStoredText(runtime.getClientId(), 50)
                && isExactStoredText(runtime.getOwnerJiacn(), 50)
                && runtime.getBindingId() != null
                && runtime.getBindingId() > 0
                && Objects.equals(expectedClientId, runtime.getClientId())
                && (expectedOwnerJiacn == null
                        || Objects.equals(expectedOwnerJiacn, runtime.getOwnerJiacn()));
        if (!valid) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "Agent runtime batch row is outside the byte-exact requested scope");
        }
    }

    private void validateTaskStatsRow(AgentTaskStatsRow row) {
        if (row == null || !isExactStoredText(row.getTenantId(), 50)
                || !isExactStoredText(row.getClientId(), 50)
                || !isExactStoredText(row.getAgentId(), 100)
                || negative(row.getTaskCount())
                || negative(row.getCompletedTaskCount())
                || negative(row.getFailedTaskCount())
                || negative(row.getCompletedDurationCount())
                || negative(row.getCompletedDurationSeconds())) {
            throw new IllegalArgumentException("Agent task statistics row is invalid");
        }
        require(row.getTaskCount() < TASK_MEMBERSHIP_SNAPSHOT_LIMIT,
                "Agent task statistics snapshot exceeds the safe limit");
        require(row.getCompletedTaskCount() <= row.getTaskCount()
                        && row.getFailedTaskCount() <= row.getTaskCount()
                        && row.getCompletedDurationCount() <= row.getCompletedTaskCount(),
                "Agent task statistics aggregate is inconsistent");
    }

    private boolean negative(Long value) {
        return value == null || value < 0;
    }

    private AgentStatsDTO buildStats(AgentRuntimeEntity entity, AgentPersonaEntity persona,
            AgentTaskStatsRow taskStats) {
        AgentStatsDTO stats = new AgentStatsDTO();
        if (persona != null) {
            stats.setPower(persona.getPower());
            stats.setIntelligence(persona.getIntelligence());
            stats.setLeadership(persona.getLeadership());
        }
        if (taskStats == null) {
            stats.setCompletedTaskCount(0);
            stats.setFailedTaskCount(0);
            return stats;
        }
        stats.setCompletedTaskCount(Math.toIntExact(taskStats.getCompletedTaskCount()));
        stats.setFailedTaskCount(Math.toIntExact(taskStats.getFailedTaskCount()));
        if (taskStats.getCompletedDurationCount() > 0) {
            stats.setAverageDurationSeconds(taskStats.getCompletedDurationSeconds()
                    / taskStats.getCompletedDurationCount());
        }
        return stats;
    }

    private record RuntimeScopeKey(String tenantId, String clientId, String agentId) {
        private static RuntimeScopeKey from(AgentRuntimeEntity runtime) {
            if (runtime == null || StringUtil.isBlank(runtime.getOwnerJiacn())
                    || StringUtil.isBlank(runtime.getClientId())
                    || StringUtil.isBlank(runtime.getAgentId())) {
                return null;
            }
            return new RuntimeScopeKey(
                    runtime.getOwnerJiacn(), runtime.getClientId(), runtime.getAgentId());
        }

        private static RuntimeScopeKey from(AgentTaskStatsRow row) {
            if (row == null) {
                return null;
            }
            return new RuntimeScopeKey(row.getTenantId(), row.getClientId(), row.getAgentId());
        }
    }

    private record RuntimeBatchEnrichment(
            Map<String, AgentPersonaEntity> personasByCode,
            Map<String, AgentPersonaEntity> personasByName,
            Map<RuntimeScopeKey, AgentTaskStatsRow> statsByScope) {
        private static RuntimeBatchEnrichment empty() {
            return new RuntimeBatchEnrichment(Map.of(), Map.of(), Map.of());
        }

        private AgentPersonaEntity resolvePersona(AgentRuntimeEntity runtime) {
            AgentPersonaEntity byCode = StringUtil.isBlank(runtime.getPersonaCode())
                    ? null : personasByCode.get(runtime.getPersonaCode());
            return byCode != null || StringUtil.isBlank(runtime.getPersonaName())
                    ? byCode : personasByName.get(runtime.getPersonaName());
        }

        private AgentPersonaEntity personaByCode(String personaCode) {
            return personasByCode.get(personaCode);
        }

        private AgentTaskStatsRow taskStats(AgentRuntimeEntity runtime) {
            RuntimeScopeKey key = RuntimeScopeKey.from(runtime);
            return key == null ? null : statsByScope.get(key);
        }
    }

    private AgentStatsDTO buildStats(AgentRuntimeEntity entity) {
        AgentStatsDTO stats = new AgentStatsDTO();
        AgentPersonaEntity persona = resolvePersona(entity);
        if (persona != null) {
            stats.setPower(persona.getPower());
            stats.setIntelligence(persona.getIntelligence());
            stats.setLeadership(persona.getLeadership());
        }
        String tenantId = entity.getOwnerJiacn();
        String clientId = entity.getClientId();
        List<AgentTaskMetaEntity> tasks = StringUtil.isBlank(tenantId) || StringUtil.isBlank(clientId)
                ? List.of()
                : Optional.ofNullable(agentTaskMetaDao.findByAgentId(
                        tenantId, clientId, entity.getAgentId(), TASK_MEMBERSHIP_SNAPSHOT_LIMIT))
                        .orElseGet(Collections::emptyList);
        require(tasks.size() < TASK_MEMBERSHIP_SNAPSHOT_LIMIT,
                "Agent task statistics snapshot exceeds the safe limit");
        tasks.forEach(task -> requireScopedLegacyAgentTaskProjection(
                task, tenantId, clientId, entity.getAgentId()));
        stats.setCompletedTaskCount((int) tasks.stream()
                .filter(task -> AgentConstants.TASK_STATUS_COMPLETED.equals(task.getRewardStatus()))
                .count());
        stats.setFailedTaskCount((int) tasks.stream()
                .filter(task -> AgentConstants.TASK_STATUS_FAILED.equals(task.getRewardStatus()))
                .count());

        long durationCount = tasks.stream()
                .filter(task -> AgentConstants.TASK_STATUS_COMPLETED.equals(task.getRewardStatus()))
                .filter(task -> task.getStartedAt() != null && task.getCompletedAt() != null)
                .filter(task -> task.getCompletedAt() >= task.getStartedAt())
                .count();
        if (durationCount > 0) {
            long durationSeconds = tasks.stream()
                    .filter(task -> AgentConstants.TASK_STATUS_COMPLETED.equals(task.getRewardStatus()))
                    .filter(task -> task.getStartedAt() != null && task.getCompletedAt() != null)
                    .filter(task -> task.getCompletedAt() >= task.getStartedAt())
                    .mapToLong(task -> (task.getCompletedAt() - task.getStartedAt()) / 1000)
                    .sum();
            stats.setAverageDurationSeconds(durationSeconds / durationCount);
        }
        return stats;
    }

    private AgentCapabilityDTO toCapabilityDTO(AgentRuntimeDTO agent) {
        AgentCapabilityDTO dto = new AgentCapabilityDTO();
        dto.setAgentId(agent.getAgentId());
        dto.setName(agent.getName());
        dto.setPersonaCode(agent.getPersonaCode());
        dto.setPersonaName(agent.getPersonaName());
        dto.setAbilities(Optional.ofNullable(agent.getAbilities()).orElseGet(Collections::emptyList));
        dto.setRoles(resolveCapabilityRoles(agent));
        dto.setStatus(agent.getStatus());
        dto.setCurrentTaskId(agent.getCurrentTaskId());
        dto.setCurrentTaskTitle(agent.getCurrentTaskTitle());
        dto.setCurrentLoad(resolveCurrentLoad(agent));
        dto.setSuccessRate(resolveSuccessRate(agent.getStats()));
        dto.setRecentScore(resolveRecentScore(agent.getStats()));
        dto.setCollaborationHint(resolveCollaborationHint(dto.getRoles()));
        dto.setSystemAgent(Boolean.TRUE.equals(agent.getSystemAgent()));
        dto.setCanOperate(Boolean.TRUE.equals(agent.getCanOperate()));
        return dto;
    }

    private AgentTaskRecommendationDTO buildTaskRecommendation(AgentTaskDTO task, AgentRuntimeEntity entity) {
        AgentRuntimeDTO agent = toRuntimeDTO(entity);
        AgentCapabilityDTO capability = toCapabilityDTO(agent);
        List<String> required = Optional.ofNullable(task.getRequiredAbilities()).orElseGet(Collections::emptyList);
        List<String> matchedAbilities = matchedAbilities(required, capability.getAbilities());
        int abilityScore = required.isEmpty() ? 80 : clamp(matchedAbilities.size() * 100 / required.size());
        int statusScore = statusScore(capability.getStatus());
        int successScore = clamp((int) Math.round(Optional.ofNullable(capability.getSuccessRate()).orElse(0.75D) * 100));
        int loadScore = Optional.ofNullable(capability.getCurrentLoad()).orElse(0) > 0 ? 20 : 100;
        int recentScore = Optional.ofNullable(capability.getRecentScore()).orElse(75);
        int totalScore = clamp(Math.round(abilityScore * 0.4F + statusScore * 0.2F
                + successScore * 0.2F + loadScore * 0.1F + recentScore * 0.1F));

        AgentTaskRecommendationDTO dto = new AgentTaskRecommendationDTO();
        dto.setTaskId(task.getId());
        dto.setAgent(agent);
        dto.setCapability(capability);
        dto.setScore(totalScore);
        dto.setAbilityScore(abilityScore);
        dto.setStatusScore(statusScore);
        dto.setSuccessScore(successScore);
        dto.setLoadScore(loadScore);
        dto.setRecentScore(recentScore);
        dto.setMatchedAbilities(matchedAbilities);
        dto.setReason(recommendationReason(capability, matchedAbilities, required, totalScore));
        return dto;
    }

    private List<String> selectAutoAssignAgentIds(AgentTaskDTO task, List<AgentTaskRecommendationDTO> recommendations) {
        List<String> required = Optional.ofNullable(task.getRequiredAbilities()).orElseGet(Collections::emptyList);
        if (required.isEmpty()) {
            return recommendations.stream()
                    .filter(this::isOnlineRecommendation)
                    .findFirst()
                    .map(recommendation -> List.of(recommendation.getAgent().getAgentId()))
                    .orElseGet(List::of);
        }

        Set<String> remaining = required.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<String> selected = new ArrayList<>();
        for (AgentTaskRecommendationDTO recommendation : recommendations) {
            if (!isOnlineRecommendation(recommendation)) {
                continue;
            }
            boolean contributes = recommendation.getMatchedAbilities().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(remaining::contains);
            if (!contributes && !selected.isEmpty()) {
                continue;
            }
            selected.add(recommendation.getAgent().getAgentId());
            recommendation.getMatchedAbilities().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .forEach(remaining::remove);
            if (remaining.isEmpty() || selected.size() >= 3) {
                break;
            }
        }
        if (!remaining.isEmpty()) {
            return List.of();
        }
        return selected;
    }

    private boolean isOnlineRecommendation(AgentTaskRecommendationDTO recommendation) {
        return recommendation.getAgent() != null
                && AgentConstants.STATUS_ONLINE.equals(recommendation.getAgent().getStatus());
    }

    private List<String> matchedAbilities(List<String> requiredAbilities, List<String> agentAbilities) {
        if (requiredAbilities == null || requiredAbilities.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> agentAbilitySet = Optional.ofNullable(agentAbilities).orElseGet(Collections::emptyList)
                .stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        return requiredAbilities.stream()
                .filter(ability -> agentAbilitySet.contains(String.valueOf(ability).toLowerCase(Locale.ROOT)))
                .toList();
    }

    private int statusScore(String status) {
        if (AgentConstants.STATUS_ONLINE.equals(status)) {
            return 100;
        }
        if (AgentConstants.STATUS_BUSY.equals(status)) {
            return 40;
        }
        return 0;
    }

    private int resolveCurrentLoad(AgentRuntimeDTO agent) {
        if (AgentConstants.STATUS_BUSY.equals(agent.getStatus()) || !StringUtil.isBlank(agent.getCurrentTaskId())) {
            return 1;
        }
        return 0;
    }

    private Double resolveSuccessRate(AgentStatsDTO stats) {
        if (stats == null) {
            return 0.75D;
        }
        int completed = Optional.ofNullable(stats.getCompletedTaskCount()).orElse(0);
        int failed = Optional.ofNullable(stats.getFailedTaskCount()).orElse(0);
        int total = completed + failed;
        if (total == 0) {
            return 0.75D;
        }
        return (double) completed / total;
    }

    private int resolveRecentScore(AgentStatsDTO stats) {
        if (stats == null) {
            return 75;
        }
        int completed = Optional.ofNullable(stats.getCompletedTaskCount()).orElse(0);
        int failed = Optional.ofNullable(stats.getFailedTaskCount()).orElse(0);
        return clamp(75 + completed * 4 - failed * 12);
    }

    private List<String> resolveCapabilityRoles(AgentRuntimeDTO agent) {
        if (Boolean.TRUE.equals(agent.getSystemAgent())) {
            return List.of("leader", "dispatcher", "coordinator");
        }
        Set<String> roles = new LinkedHashSet<>();
        Set<String> abilities = Optional.ofNullable(agent.getAbilities()).orElseGet(Collections::emptyList)
                .stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (hasAny(abilities, "planning", "analysis", "dispatch", "coordination", "briefing")) {
            roles.add("planner");
        }
        if (hasAny(abilities, "review", "test", "testing", "quality", "verify")) {
            roles.add("reviewer");
        }
        if (hasAny(abilities, "execution", "execute", "backend", "frontend", "debug", "debugging", "research")) {
            roles.add("executor");
        }
        if (roles.isEmpty()) {
            roles.add("executor");
        }
        return new ArrayList<>(roles);
    }

    private boolean hasAny(Set<String> values, String... candidates) {
        return Arrays.stream(candidates).anyMatch(values::contains);
    }

    private String resolveCollaborationHint(List<String> roles) {
        if (roles.contains("leader")) {
            return "宋江首领负责议事、拆解、派令、追踪和复盘。";
        }
        if (roles.contains("planner")) {
            return "适合任务拆解、方案评审、风险判断和协同安排。";
        }
        if (roles.contains("reviewer")) {
            return "适合结果复核、质量检查和验收把关。";
        }
        return "适合承接执行任务，并在需要时向其他好汉请求协助。";
    }

    private String recommendationReason(AgentCapabilityDTO capability, List<String> matchedAbilities,
            List<String> requiredAbilities, int score) {
        String abilityPart = requiredAbilities.isEmpty()
                ? "该悬赏未限定能力"
                : "匹配 " + matchedAbilities.size() + "/" + requiredAbilities.size() + " 项能力";
        String statusPart = AgentConstants.STATUS_ONLINE.equals(capability.getStatus()) ? "在线候命" : "当前忙碌";
        return "宋江首领建议：" + abilityPart + "，" + statusPart + "，综合分 " + score + "。";
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private AgentTaskDTO toSearchTaskDTO(AgentTaskSearchRow row,
            List<String> assignedAgentIds, Map<String, AgentRuntimeEntity> runtimesById) {
        AgentTaskDTO dto = new AgentTaskDTO();
        dto.setId(row.getTaskId());
        dto.setTenantId(row.getTenantId());
        dto.setClientId(row.getClientId());
        dto.setTitle(StringUtil.isBlank(row.getPlanTitle())
                ? row.getTaskId() : row.getPlanTitle());
        dto.setDescription(row.getPlanDescription());
        dto.setStatus(row.getRewardStatus());
        dto.setRequiredAbilities(parseList(row.getRequiredAbilities()));
        dto.setReward(row.getReward() == null ? row.getPlanReward() : row.getReward());
        List<String> exactAgentIds = assignedAgentIds == null
                ? List.of() : List.copyOf(new LinkedHashSet<>(assignedAgentIds));
        List<AgentTaskAssigneeDTO> assignees = new ArrayList<>();
        for (String agentId : exactAgentIds) {
            require(isExactStoredText(agentId, 100),
                    "Persisted task assignee ID is invalid");
            AgentRuntimeEntity runtime = runtimesById.get(agentId);
            AgentTaskAssigneeDTO assignee = new AgentTaskAssigneeDTO();
            assignee.setAgentId(agentId);
            assignee.setAgentName(runtime == null ? null : runtime.getName());
            assignee.setStatus(runtime == null ? null : runtime.getStatus());
            assignees.add(assignee);
        }
        dto.setAssignedAgentIds(exactAgentIds);
        dto.setAssignees(List.copyOf(assignees));
        dto.setAssignedAgentId(exactAgentIds.isEmpty() ? null : exactAgentIds.getFirst());
        dto.setAssignedAgentName(assignees.isEmpty()
                ? null : assignees.getFirst().getAgentName());
        dto.setCreatedAt(row.getCreateTime() == null
                ? row.getPlanCreateTime() : row.getCreateTime());
        dto.setUpdatedAt(row.getUpdateTime() == null
                ? row.getPlanUpdateTime() : row.getUpdateTime());
        dto.setAssignedAt(row.getAssignedAt());
        dto.setStartedAt(row.getStartedAt());
        dto.setCompletedAt(row.getCompletedAt());
        dto.setFailureReason(row.getFailureReason());
        dto.setTaskVersion(row.getTaskVersion() == null
                ? null : Long.toString(row.getTaskVersion()));
        applySearchFundingProjection(dto, row);
        return dto;
    }

    private void applySearchFundingProjection(AgentTaskDTO dto, AgentTaskSearchRow row) {
        if (Integer.valueOf(0).equals(row.getFundingPresent())) {
            require(row.getFundingProjectionValid() == null
                            && row.getFundingMode() == null
                            && row.getFundingStatus() == null
                            && row.getEscrowId() == null
                            && row.getGrossBountyAmountMicro() == null
                            && row.getRemainingMicro() == null
                            && row.getRequiredSkillRequirements() == null,
                    "Persisted unfunded task search projection is contaminated");
            dto.setRequiredSkillRequirements(List.of());
            return;
        }
        require(Integer.valueOf(1).equals(row.getFundingPresent())
                        && Integer.valueOf(1).equals(row.getFundingProjectionValid())
                        && "FUNDED_SINGLE_AGENT".equals(row.getFundingMode())
                        && ("FUNDS_HELD".equals(row.getFundingStatus())
                            || "REFUNDED".equals(row.getFundingStatus())
                            || "SETTLED".equals(row.getFundingStatus()))
                        && !StringUtil.isBlank(row.getEscrowId())
                        && row.getGrossBountyAmountMicro() != null
                        && row.getGrossBountyAmountMicro() > 0
                        && row.getRemainingMicro() != null
                        && row.getRemainingMicro() >= 0
                        && row.getRemainingMicro() <= row.getGrossBountyAmountMicro(),
                "Persisted funded task search projection is corrupt");
        String requirementsJson = row.getRequiredSkillRequirements();
        List<AgentSkillRequirementDTO> requirements = JsonUtil.jsonToList(
                requirementsJson, AgentSkillRequirementDTO.class);
        require(requirements != null
                        && (!requirements.isEmpty() || "[]".equals(requirementsJson)),
                "Persisted funded task skill requirements are corrupt");
        AgentTaskFundingDTO funding = new AgentTaskFundingDTO();
        funding.setMode(row.getFundingMode());
        funding.setStatus(row.getFundingStatus());
        funding.setEscrowId(row.getEscrowId());
        funding.setGrossBountyAmountMicro(
                Long.toString(row.getGrossBountyAmountMicro()));
        funding.setRemainingMicro(Long.toString(row.getRemainingMicro()));
        dto.setFunding(funding);
        dto.setRequiredSkillRequirements(List.copyOf(requirements));
    }

    private AgentTaskDTO toTaskDTO(AgentTaskMetaEntity meta) {
        AgentTaskDTO dto = new AgentTaskDTO();
        dto.setId(meta.getTaskId());
        dto.setTenantId(meta.getTenantId());
        dto.setClientId(meta.getClientId());
        dto.setTitle(meta.getTaskId());
        dto.setStatus(meta.getRewardStatus());
        dto.setRequiredAbilities(parseList(meta.getRequiredAbilities()));
        dto.setReward(meta.getReward());
        List<String> assignedAgentIds = resolveTaskAssigneeIds(meta);
        applyTaskAssignees(dto, assignedAgentIds);
        dto.setCreatedAt(meta.getCreateTime());
        dto.setUpdatedAt(meta.getUpdateTime());
        dto.setAssignedAt(meta.getAssignedAt());
        dto.setStartedAt(meta.getStartedAt());
        dto.setCompletedAt(meta.getCompletedAt());
        dto.setFailureReason(meta.getFailureReason());
        dto.setTaskVersion(meta.getTaskVersion() == null ? null : Long.toString(meta.getTaskVersion()));
        FundedBountyService fundingService = this.fundedBountyService;
        if (fundingService != null) {
            dto.setFunding(fundingService.findFunding(meta.getTenantId(), meta.getClientId(), meta.getTaskId()));
            dto.setRequiredSkillRequirements(fundingService.requiredSkills(
                    meta.getTenantId(), meta.getClientId(), meta.getTaskId()));
        }
        enrichTaskPlan(dto, meta.getTaskId());
        return dto;
    }

    private void requireLegacyAssignmentAllowed(String tenantId, String clientId, String taskId,
            boolean automatic, int targetCount) {
        fundedBountyLegacyGuard.requireAssignmentAllowed(
                tenantId, clientId, taskId, automatic, targetCount, false);
    }

    private void requireLegacyAssignmentAllowedLocked(String tenantId, String clientId, String taskId,
            boolean automatic, int targetCount) {
        fundedBountyLegacyGuard.requireAssignmentAllowed(
                tenantId, clientId, taskId, automatic, targetCount, true);
    }

    private void requireLegacyLifecycleAllowed(String tenantId, String clientId, String taskId) {
        fundedBountyLegacyGuard.requireLifecycleAllowed(tenantId, clientId, taskId, false);
    }

    private void requireLegacyLifecycleAllowedLocked(String tenantId, String clientId, String taskId) {
        fundedBountyLegacyGuard.requireLifecycleAllowed(tenantId, clientId, taskId, true);
    }

    private void applyAssignmentProjection(
            AgentTaskMetaEntity meta, List<String> agentIds) {
        require(agentIds != null && !agentIds.isEmpty(), "assigned agentIds are required");
        String primaryAgentId = agentIds.getFirst();
        meta.setAssignedAgentId(primaryAgentId);
        meta.setRewardStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        meta.setCollaborationMode(agentIds.size() == 1 ? "single" : "team");
        meta.setMaxAgents(agentIds.size());
        meta.setCoordinatorAgentId(primaryAgentId);
    }

    private List<String> resolveTaskAssigneeIds(AgentTaskMetaEntity meta) {
        if (meta != null && !StringUtil.isBlank(meta.getTenantId())
                && !StringUtil.isBlank(meta.getClientId())
                && !StringUtil.isBlank(meta.getTaskId())) {
            List<String> memberAgentIds = listTaskMemberAgentIds(
                    meta.getTenantId(), meta.getClientId(), meta.getTaskId());
            if (!memberAgentIds.isEmpty()) {
                return memberAgentIds;
            }
        }
        return meta == null ? List.of() : parseAssignedAgentIds(meta.getAssignedAgentId());
    }

    private void applyTaskAssignees(AgentTaskDTO task, List<String> agentIds) {
        List<String> exactAgentIds = agentIds == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(agentIds));
        task.setAssignedAgentIds(exactAgentIds);
        task.setAssignees(exactAgentIds.stream().map(this::toAssigneeDTO).toList());
        task.setAssignedAgentId(exactAgentIds.isEmpty() ? null : exactAgentIds.getFirst());
        task.setAssignedAgentName(task.getAssignees().isEmpty()
                ? null : task.getAssignees().getFirst().getAgentName());
    }

    private AgentTaskNoteDTO toTaskNoteDTO(AgentTaskNoteEntity entity) {
        AgentTaskNoteDTO dto = new AgentTaskNoteDTO();
        dto.setTaskId(entity.getTaskId());
        dto.setAuthorId(entity.getAuthorId());
        dto.setAuthorType(entity.getAuthorType());
        dto.setNoteType(entity.getNoteType());
        dto.setContent(entity.getContent());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private AgentTaskAssigneeDTO toAssigneeDTO(String agentId) {
        AgentTaskAssigneeDTO dto = new AgentTaskAssigneeDTO();
        dto.setAgentId(agentId);
        AgentRuntimeEntity agent = agentRuntimeDao.findByAgentId(agentId);
        dto.setAgentName(agent == null ? null : agent.getName());
        dto.setStatus(agent == null ? null : agent.getStatus());
        return dto;
    }

    private void enrichTaskPlan(AgentTaskDTO dto, String taskId) {
        if (StringUtil.isBlank(taskId)) {
            return;
        }
        TaskService taskService = taskServiceProvider.getIfAvailable();
        if (taskService == null) {
            return;
        }
        try {
            TaskPlanEntity task = taskService.get(Long.valueOf(taskId));
            if (task == null) {
                return;
            }
            if (!StringUtil.isBlank(task.getName())) {
                dto.setTitle(task.getName());
            }
            dto.setDescription(task.getDescription());
            if (dto.getReward() == null && task.getAmount() != null) {
                dto.setReward(task.getAmount().intValue());
            }
            if (dto.getCreatedAt() == null) {
                dto.setCreatedAt(task.getCreateTime());
            }
            if (dto.getUpdatedAt() == null) {
                dto.setUpdatedAt(task.getUpdateTime());
            }
        } catch (NumberFormatException ignored) {
            // AgentTaskMeta may use external string IDs from OpenClaw.
        }
    }

    private List<String> parseList(String json) {
        if (StringUtil.isBlank(json) || "[]".equals(json.trim())) {
            return Collections.emptyList();
        }
        List<String> parsed = JsonUtil.jsonToList(json, String.class);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        return Arrays.stream(json.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> parseAssignedAgentIds(String value) {
        return parseList(value);
    }

    private void validateLegacyAssignableTask(AgentTaskMetaEntity meta) {
        if (meta.getId() == null) {
            return;
        }
        AgentTaskStatus status;
        try {
            status = AgentTaskStatus.fromPersistedValue(meta.getRewardStatus());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Persisted task status is non-canonical or unknown");
        }
        require(status == AgentTaskStatus.OPEN
                        || status == AgentTaskStatus.PLANNING
                        || status == AgentTaskStatus.ASSIGNED,
                "Task cannot be assigned in its current status");
        require(meta.getTaskVersion() != null && meta.getTaskVersion() >= 0
                        && meta.getTaskVersion() != Long.MAX_VALUE,
                "Persisted task version is invalid");
    }

    private void validateAgentStatus(String status) {
        require(List.of(AgentConstants.STATUS_ONLINE, AgentConstants.STATUS_BUSY, AgentConstants.STATUS_OFFLINE,
                AgentConstants.STATUS_ERROR).contains(status), "Invalid agent status");
    }

    private void validateTaskStatus(String status) {
        require(List.of(AgentConstants.TASK_STATUS_OPEN, AgentConstants.TASK_STATUS_ASSIGNED,
                AgentConstants.TASK_STATUS_RUNNING, AgentConstants.TASK_STATUS_COMPLETED,
                AgentConstants.TASK_STATUS_FAILED, AgentConstants.TASK_STATUS_ARCHIVED).contains(status), "Invalid task status");
    }

    private String resolveAgentStatus(String taskStatus) {
        return switch (taskStatus) {
            case AgentConstants.TASK_STATUS_RUNNING -> AgentConstants.STATUS_BUSY;
            case AgentConstants.TASK_STATUS_FAILED -> AgentConstants.STATUS_ERROR;
            default -> AgentConstants.STATUS_ONLINE;
        };
    }

    private AgentRuntimeDTO updateLegacyReportRuntime(
            String agentId, String taskId, String reportedStatus,
            String currentTaskTitle, String failureReason, boolean wholeTeamTerminal) {
        AgentRuntimeEntity entity = requireAgent(agentId);
        requireOwnedAgent(entity);
        entity.setStatus(resolveAgentStatus(reportedStatus));
        boolean memberTerminal = AgentConstants.TASK_STATUS_COMPLETED.equals(reportedStatus)
                || AgentConstants.TASK_STATUS_FAILED.equals(reportedStatus);
        if (wholeTeamTerminal || memberTerminal) {
            entity.setCurrentTaskId(null);
            entity.setCurrentTaskTitle(null);
        } else {
            entity.setCurrentTaskId(taskId);
            entity.setCurrentTaskTitle(currentTaskTitle);
        }
        entity.setErrorMessage(AgentConstants.TASK_STATUS_FAILED.equals(reportedStatus)
                ? failureReason : null);
        entity.setLastSeenAt(System.currentTimeMillis());
        require(agentRuntimeDao.updateById(entity) == 1, "Agent runtime update failed");
        observeSkillLifecycle(entity);
        return toRuntimeDTO(entity);
    }

    private String terminalRuntimeReportStatus(
            String agentId, String reportingAgentId, String aggregateStatus) {
        if (AgentConstants.TASK_STATUS_FAILED.equals(aggregateStatus)
                && Objects.equals(agentId, reportingAgentId)) {
            return AgentConstants.TASK_STATUS_FAILED;
        }
        return AgentConstants.TASK_STATUS_COMPLETED;
    }

    private void publishTaskAssignmentSideEffectsAfterCommit(
            AgentTaskDTO task, List<AgentRuntimeEntity> assignedAgents,
            boolean durableAssignmentDelivery) {
        List<AgentRuntimeEntity> agents = List.copyOf(assignedAgents);
        publishOptionalAfterCommit("task-assignment", () -> {
            if (!durableAssignmentDelivery) {
                task.setActionDispatchResults(dispatchTaskAssignedActions(task, agents));
            }
            publishTaskEvent("task_assigned", task);
        });
    }

    private void publishLegacyReportSideEffectsAfterCommit(
            String eventType, AgentTaskDTO task, List<AgentRuntimeDTO> updatedAgents) {
        List<AgentRuntimeDTO> agents = List.copyOf(updatedAgents);
        String clientId = task.getClientId();
        String ownerJiacn = task.getTenantId();
        publishOptionalAfterCommit("legacy-task-report", () -> {
            publishScopedAgentSnapshots(clientId, ownerJiacn, agents);
            publishTaskEvent(eventType, task);
        });
    }

    private void publishTaskAssignmentSceneStates(
            String taskId, List<AgentRuntimeEntity> assignedAgents) {
        List<AgentRuntimeEntity> agents = List.copyOf(assignedAgents);
        publishOptionalSceneAfterCommit("task-assignment", () -> {
            AgentSceneService sceneService = sceneServiceProvider.getIfAvailable();
            if (sceneService == null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (AgentRuntimeEntity agent : agents) {
                publishSceneState(sceneService, movementState(
                        agent.getAgentId(), agent.getPersonaCode(), "moving_to_bounty",
                        REGION_BOUNTY_BOARD, "task", taskId, now));
            }
        });
    }

    private void publishDiscussionSceneState(String personaName, String dialogueType) {
        String normalizedType = Optional.ofNullable(dialogueType).orElse("")
                .trim().toUpperCase(Locale.ROOT);
        if (!"DISCUSSION".equals(normalizedType) && !"CHAT".equals(normalizedType)) {
            return;
        }
        publishOptionalSceneAfterCommit("dialogue", () -> {
            AgentSceneService sceneService = sceneServiceProvider.getIfAvailable();
            if (sceneService == null) {
                return;
            }
            Optional<AgentPersonaEntity> persona = resolveUniquePersonaIdentity(personaName);
            if (persona.isEmpty()) {
                return;
            }
            List<AgentRuntimeEntity> matches = Optional.ofNullable(agentRuntimeDao.findRosterByOwner(
                            resolveCurrentClientId(), resolveCurrentJiacn(), null, null))
                    .orElseGet(Collections::emptyList)
                    .stream()
                    .filter(agent -> persona.get().getPersonaCode().equalsIgnoreCase(
                            Optional.ofNullable(agent.getPersonaCode()).orElse("")))
                    .toList();
            if (matches.size() != 1) {
                log.warn("Skipping optional dialogue scene publication because persona roster identity is not unique");
                return;
            }
            AgentRuntimeEntity agent = matches.getFirst();
            String relatedType = "CHAT".equals(normalizedType) ? "chat" : "discussion";
            String relatedId = "dlg-" + UUID.randomUUID().toString().replace("-", "");
            long now = System.currentTimeMillis();
            publishSceneState(sceneService, movementState(
                    agent.getAgentId(), agent.getPersonaCode(), "moving_to_discussion",
                    REGION_COUNCIL_TABLE, relatedType, relatedId, now));
        });
    }

    private void publishReturnHomeSceneStates(String taskId, List<AgentRuntimeDTO> agents) {
        List<AgentRuntimeDTO> copiedAgents = List.copyOf(agents);
        publishOptionalSceneAfterCommit("task-return-home", () -> {
            AgentSceneService sceneService = sceneServiceProvider.getIfAvailable();
            if (sceneService == null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (AgentRuntimeDTO agent : copiedAgents) {
                publishSceneState(sceneService, movementState(
                        agent.getAgentId(), agent.getPersonaCode(), "returning_home",
                        REGION_MAIN_SEAT, "task", taskId, now));
            }
        });
    }

    private void publishReturnHomeSceneStatesForAgentIds(String taskId, List<String> agentIds) {
        List<String> copiedAgentIds = List.copyOf(agentIds);
        publishOptionalSceneAfterCommit("task-archive-return-home", () -> {
            AgentSceneService sceneService = sceneServiceProvider.getIfAvailable();
            if (sceneService == null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (String agentId : copiedAgentIds) {
                AgentRuntimeEntity agent = agentRuntimeDao.findByAgentId(agentId);
                requireOwnedAgent(agent);
                publishSceneState(sceneService, movementState(
                        agent.getAgentId(), agent.getPersonaCode(), "returning_home",
                        REGION_MAIN_SEAT, "task", taskId, now));
            }
        });
    }

    private AgentSceneStateDTO movementState(
            String agentId,
            String personaCode,
            String behavior,
            String targetRegionId,
            String relatedType,
            String relatedId,
            long now) {
        AgentSceneStateDTO state = new AgentSceneStateDTO();
        state.setAgentId(agentId);
        state.setPersonaCode(personaCode);
        state.setBehavior(behavior);
        state.setTargetRegionId(targetRegionId);
        state.setRelatedType(relatedType);
        state.setRelatedId(relatedId);
        state.setPhase("moving");
        state.setStartedAt(now);
        state.setExpectedArrivalAt(now + SCENE_EXPECTED_ARRIVAL_MILLIS);
        state.setExpiresAt(now + SCENE_STATE_EXPIRY_MILLIS);
        return state;
    }

    private void publishSceneState(AgentSceneService sceneService, AgentSceneStateDTO state) {
        if (StringUtil.isBlank(state.getAgentId()) || StringUtil.isBlank(state.getPersonaCode())) {
            return;
        }
        sceneService.upsertState(AgentSceneConstants.SCENE_JUYITING_MAIN, state);
    }

    private Optional<AgentPersonaEntity> resolveUniquePersonaIdentity(String personaIdentity) {
        String normalized = Optional.ofNullable(personaIdentity).orElse("").trim();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        AgentPersonaEntity byCode = agentPersonaDao.findByCode(normalized);
        AgentPersonaEntity byName = agentPersonaDao.findByName(normalized);
        if (byCode != null && byName != null
                && !Objects.equals(byCode.getPersonaCode(), byName.getPersonaCode())) {
            log.warn("Skipping optional dialogue scene publication because persona identity is ambiguous");
            return Optional.empty();
        }
        return Optional.ofNullable(byCode != null ? byCode : byName);
    }

    private void publishOptionalSceneAfterCommit(String operation, Runnable publication) {
        if (!sceneFeatureFlags.sceneStateEnabled()) {
            return;
        }
        publishOptionalAfterCommit(operation, publication);
    }

    private void publishOptionalAfterCommit(String operation, Runnable publication) {
        Runnable isolatedPublication = () -> {
            try {
                publication.run();
            } catch (RuntimeException failure) {
                log.warn("Optional after-commit side effect failed: operation={}, failureType={}",
                        operation, failure.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        isolatedPublication.run();
                    }
                });
            } catch (RuntimeException registrationFailure) {
                log.warn("Optional after-commit side effect could not be scheduled: "
                                + "operation={}, failureType={}",
                        operation, registrationFailure.getClass().getSimpleName());
            }
            return;
        }
        isolatedPublication.run();
    }

    private void publishAgentSnapshotAfterCommit(
            String operation, String tenantId, String clientId, String ownerJiacn,
            String agentId, long bindingId) {
        publishOptionalAfterCommit(operation, () ->
                scopePublicationCoordinator.execute(clientId, ownerJiacn, () -> {
                    AgentHostedBindingTransaction.Scope scope =
                            new AgentHostedBindingTransaction.Scope(tenantId, clientId, ownerJiacn);
                    AgentRuntimeEntity current = runtimePublicationWorker
                            .revalidateForPublication(scope, agentId, bindingId);
                    if (current == null) {
                        return;
                    }
                    publishAgentSnapshots(clientId, ownerJiacn,
                            List.of(toRuntimeDTO(current, clientId, ownerJiacn)),
                            listCapabilities(clientId, ownerJiacn));
                }));
    }

    private void publishScopedAgentSnapshots(
            String clientId, String ownerJiacn, List<AgentRuntimeDTO> agents) {
        scopePublicationCoordinator.execute(clientId, ownerJiacn,
                () -> publishAgentSnapshots(clientId, ownerJiacn, agents,
                        listCapabilities(clientId, ownerJiacn)));
    }

    private void publishAgentSnapshots(
            String clientId, String ownerJiacn, List<AgentRuntimeDTO> agents,
            List<AgentCapabilityDTO> capabilities) {
        Optional.ofNullable(eventPublisherProvider.getIfAvailable()).ifPresent(publisher -> {
            agents.forEach(agent -> publisher.publishAgentStatus(clientId, ownerJiacn, agent));
            publisher.publishCapabilityIndex(clientId, ownerJiacn, capabilities);
        });
    }

    private void publishTaskEvent(String eventType, AgentTaskDTO task) {
        Optional.ofNullable(eventPublisherProvider.getIfAvailable()).ifPresent(publisher -> publisher.publishTaskEvent(eventType, task));
    }

    private List<AgentActionDispatchResultDTO> dispatchTaskAssignedActions(AgentTaskDTO task, List<AgentRuntimeEntity> assignedAgents) {
        AgentEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        return assignedAgents.stream()
                .map(agent -> dispatchTaskAssignedAction(task, agent, assignedAgents, publisher))
                .toList();
    }

    private AgentActionDispatchResultDTO dispatchTaskAssignedAction(AgentTaskDTO task, AgentRuntimeEntity agent,
            List<AgentRuntimeEntity> assignedAgents, AgentEventPublisher publisher) {
        AgentActionIntentDTO intent = buildTaskBriefingIntent(task, agent, assignedAgents);
        if (publisher == null || AgentConstants.STATUS_OFFLINE.equals(agent.getStatus())) {
            return queuedAction(intent, "Agent offline or not connected");
        }
        try {
            AgentActionDispatchResultDTO result = publisher.publishAgentAction(intent);
            if (result == null) {
                result = dispatchedAction(intent);
            }
            normalizeDispatchResult(result, intent);
            log.info("Agent action intent dispatched, intentId={}, taskId={}, targetAgentId={}, status={}, message={}",
                    result.getIntentId(), result.getTaskId(), result.getTargetAgentId(), result.getStatus(), result.getMessage());
            return result;
        } catch (Exception e) {
            AgentActionDispatchResultDTO result = failedAction(intent, e.getMessage());
            log.warn("Agent action intent dispatch failed, intentId={}, taskId={}, targetAgentId={}, reason={}",
                    result.getIntentId(), result.getTaskId(), result.getTargetAgentId(), result.getMessage(), e);
            return result;
        }
    }

    private AgentActionIntentDTO buildTaskBriefingIntent(AgentTaskDTO task, AgentRuntimeEntity agent,
            List<AgentRuntimeEntity> assignedAgents) {
        AgentActionIntentDTO intent = new AgentActionIntentDTO();
        intent.setIntentId(UUID.randomUUID().toString());
        intent.setCommandId(intent.getIntentId());
        intent.setCommandType(AgentProtocolConstants.COMMAND_TASK_INVITE);
        intent.setCorrelationId(task.getId());
        intent.setTenantId(task.getTenantId());
        intent.setClientId(task.getClientId());
        intent.setActionType("task_briefing");
        intent.setActorAgentId(agent.getAgentId());
        intent.setTargetAgentIds(assignedAgents.stream().map(AgentRuntimeEntity::getAgentId).toList());
        intent.setTaskId(task.getId());
        intent.setReason("宋江首领已完成悬赏分派，请按职责协作推进。");
        intent.setInstruction("阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。");
        intent.setContext(buildTaskBriefingContext(task, assignedAgents));
        intent.setAutonomyLevel("assist");
        intent.setRequiresApproval(false);
        intent.setConversationType("juyiting");
        intent.setCreatedAt(System.currentTimeMillis());
        return intent;
    }

    private Map<String, Object> buildTaskBriefingContext(AgentTaskDTO task, List<AgentRuntimeEntity> assignedAgents) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("leader", Map.of(
                "agentId", AgentConstants.BUILTIN_SONGJIANG_AGENT_ID,
                "name", "宋江",
                "role", "首领"));
        context.put("task", Map.of(
                "taskId", Optional.ofNullable(task.getId()).orElse(""),
                "title", Optional.ofNullable(task.getTitle()).orElse(""),
                "description", Optional.ofNullable(task.getDescription()).orElse(""),
                "requiredAbilities", Optional.ofNullable(task.getRequiredAbilities()).orElseGet(Collections::emptyList)));
        context.put("collaborators", assignedAgents.stream()
                .map(agent -> toCapabilityDTO(toRuntimeDTO(agent)))
                .toList());
        context.put("acceptance", "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。");
        return context;
    }

    private AgentActionDispatchResultDTO queuedAction(AgentActionIntentDTO intent, String message) {
        AgentActionDispatchResultDTO result = new AgentActionDispatchResultDTO();
        result.setIntentId(intent.getIntentId());
        result.setTaskId(intent.getTaskId());
        result.setTargetAgentId(intent.getActorAgentId());
        result.setStatus("queued");
        result.setMessage(message);
        return result;
    }

    private AgentActionDispatchResultDTO dispatchedAction(AgentActionIntentDTO intent) {
        AgentActionDispatchResultDTO result = new AgentActionDispatchResultDTO();
        result.setIntentId(intent.getIntentId());
        result.setTaskId(intent.getTaskId());
        result.setTargetAgentId(intent.getActorAgentId());
        result.setStatus("dispatched");
        result.setDispatchedAt(System.currentTimeMillis());
        return result;
    }

    private AgentActionDispatchResultDTO failedAction(AgentActionIntentDTO intent, String message) {
        AgentActionDispatchResultDTO result = new AgentActionDispatchResultDTO();
        result.setIntentId(intent.getIntentId());
        result.setTaskId(intent.getTaskId());
        result.setTargetAgentId(intent.getActorAgentId());
        result.setStatus("failed");
        result.setMessage(message);
        return result;
    }

    private void normalizeDispatchResult(AgentActionDispatchResultDTO result, AgentActionIntentDTO intent) {
        if (StringUtil.isBlank(result.getIntentId())) {
            result.setIntentId(intent.getIntentId());
        }
        if (StringUtil.isBlank(result.getTaskId())) {
            result.setTaskId(intent.getTaskId());
        }
        if (StringUtil.isBlank(result.getTargetAgentId())) {
            result.setTargetAgentId(intent.getActorAgentId());
        }
        if (StringUtil.isBlank(result.getStatus())) {
            result.setStatus("dispatched");
        }
        if ("dispatched".equals(result.getStatus()) && result.getDispatchedAt() == null) {
            result.setDispatchedAt(System.currentTimeMillis());
        }
    }

    private String resolveCurrentJiacn() {
        EsContext context = EsContextHolder.getContext();
        if (!StringUtil.isBlank(context.getJiacn())) {
            return context.getJiacn();
        }
        if (!StringUtil.isBlank(context.getUsername())) {
            return context.getUsername();
        }
        return "juyiting";
    }

    private String resolveCurrentClientId() {
        EsContext context = EsContextHolder.getContext();
        if (!StringUtil.isBlank(context.getClientId())) {
            return context.getClientId();
        }
        return "jia_client";
    }

    private String generateAgentId() {
        return "agt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private Long requireBindingId(AgentRuntimeEntity runtime) {
        if (runtime.getBindingId() == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "Agent runtime is not linked to a durable binding");
        }
        return runtime.getBindingId();
    }

    private AgentRuntimeEntity requireExactRuntime(AgentRuntimeEntity runtime, String canonicalAgentId,
            String clientId, String ownerJiacn, Long bindingId) {
        if (!Objects.equals(canonicalAgentId, runtime.getAgentId())
                || !Objects.equals(clientId, runtime.getClientId())
                || !Objects.equals(ownerJiacn, runtime.getOwnerJiacn())
                || !Objects.equals(bindingId, runtime.getBindingId())) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                    "Agent runtime identity is not byte-exact or is outside owner scope");
        }
        return runtime;
    }

    private String limitLength(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static class AgentBizException extends RuntimeException {
        private final String code;

        public AgentBizException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
