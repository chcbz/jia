package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentCapabilityDTO;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentPersonaBindResultDTO;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentStatsDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskAssigneeDTO;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskNoteDTO;
import cn.jia.agent.entity.AgentTaskNoteEntity;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskRecommendationDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.DialogueRequestDTO;
import cn.jia.agent.entity.DialogueTemplateEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.service.TaskService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentServiceImpl implements AgentService {
    private static final String BIND_MODE_SERVER = "server";
    private static final String BIND_MODE_LOCAL = "local";
    private static final Path CODEX_WS_AGENT_DIR = Path.of("/home/isp/apps/codex-ws-agent");
    private static final Path CODEX_WS_PROFILES_FILE = CODEX_WS_AGENT_DIR.resolve("codex-profiles.conf");
    private static final Path AGENT_CLIENTS_DIR = Path.of("/home/isp/hosts/cyf/agent-clients");

    private final AgentRuntimeDao agentRuntimeDao;
    private final AgentPersonaDao agentPersonaDao;
    private final AgentPersonaBindingDao agentPersonaBindingDao;
    private final AgentTaskMetaDao agentTaskMetaDao;
    private final AgentTaskNoteDao agentTaskNoteDao;
    private final DialogueTemplateDao dialogueTemplateDao;
    private final ObjectProvider<AgentEventPublisher> eventPublisherProvider;
    private final ObjectProvider<TaskService> taskServiceProvider;
    private final ObjectProvider<ApiKeyService> apiKeyServiceProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRegisterResultDTO register(AgentRegisterDTO request) {
        require(!StringUtil.isBlank(request.getAgentId()), "agentId is required");
        if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(request.getAgentId())) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "System agent cannot register externally");
        }

        String clientId = resolveCurrentClientId();
        String jiacn = resolveCurrentJiacn();
        AgentPersonaBindingEntity binding = agentPersonaBindingDao.findActiveByClientJiacnAndAgentId(clientId, jiacn, request.getAgentId());
        if (binding == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Agent is not bound to current user");
        }
        AgentPersonaEntity persona = requirePersona(binding.getPersonaCode());

        String token = UUID.randomUUID().toString().replace("-", "");
        AgentRuntimeEntity entity = Optional.ofNullable(agentRuntimeDao.findByAgentId(request.getAgentId()))
                .orElseGet(AgentRuntimeEntity::new);
        entity.setAgentId(request.getAgentId());
        entity.setName(persona.getName());
        entity.setAvatar(StringUtil.isBlank(request.getAvatar()) ? persona.getAvatar() : request.getAvatar());
        entity.setPersonaCode(persona.getPersonaCode());
        entity.setPersonaName(persona.getName());
        entity.setOwnerJiacn(jiacn);
        entity.setBindingId(binding.getId());
        entity.setClientId(clientId);
        entity.setAbilities(StringUtil.isBlank(persona.getAbilities())
                ? JsonUtil.toJson(Optional.ofNullable(request.getAbilities()).orElseGet(Collections::emptyList))
                : persona.getAbilities());
        entity.setEndpoint(request.getEndpoint());
        entity.setTokenHash(token);
        entity.setStatus(AgentConstants.STATUS_ONLINE);
        entity.setLastSeenAt(System.currentTimeMillis());
        entity.setErrorMessage(null);

        if (entity.getId() == null) {
            agentRuntimeDao.insert(entity);
        } else {
            agentRuntimeDao.updateById(entity);
        }
        publishAgentStatus(toRuntimeDTO(entity));
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
        PageHelper.startPage(pageNum, pageSize);
        List<AgentRuntimeDTO> agents = agentRuntimeDao.findRosterByOwner(resolveCurrentClientId(), resolveCurrentJiacn(), status, ability)
                .stream()
                .map(this::toRuntimeDTO)
                .toList();
        return PageInfo.of(agents);
    }

    @Override
    public List<AgentRuntimeDTO> listMapAgents() {
        List<AgentRuntimeDTO> agents = new ArrayList<>(agentRuntimeDao.findMapVisible(resolveCurrentClientId())
                .stream()
                .map(this::toRuntimeDTO)
                .toList());
        agents.add(buildSongjiangDTO());
        return agents;
    }

    @Override
    public List<AgentCapabilityDTO> listCapabilities() {
        List<AgentRuntimeEntity> roster = Optional.ofNullable(agentRuntimeDao
                .findRosterByOwner(resolveCurrentClientId(), resolveCurrentJiacn(), null, null))
                .orElseGet(Collections::emptyList);
        List<AgentCapabilityDTO> capabilities = new ArrayList<>(roster
                .stream()
                .map(agent -> toCapabilityDTO(toRuntimeDTO(agent)))
                .toList());
        capabilities.add(toCapabilityDTO(buildSongjiangDTO()));
        return capabilities;
    }

    @Override
    public List<AgentRuntimeDTO> listPersonaCatalog() {
        String clientId = resolveCurrentClientId();
        String jiacn = resolveCurrentJiacn();
        return agentPersonaDao.selectAll().stream()
                .filter(persona -> persona.getActive() == null || Boolean.TRUE.equals(persona.getActive()))
                .sorted((left, right) -> Integer.compare(Optional.ofNullable(left.getRankNo()).orElse(Integer.MAX_VALUE),
                        Optional.ofNullable(right.getRankNo()).orElse(Integer.MAX_VALUE)))
                .map(persona -> toCatalogDTO(persona, clientId, jiacn))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRuntimeDTO bindPersona(String personaCode) {
        String clientId = resolveCurrentClientId();
        String jiacn = resolveCurrentJiacn();
        AgentPersonaEntity persona = requirePersona(personaCode);
        if (Boolean.TRUE.equals(persona.getSystemAgent())) {
            throw new AgentBizException(AgentErrorConstants.PERSONA_NOT_BINDABLE, "System persona cannot be bound");
        }
        AgentPersonaBindingEntity bound = agentPersonaBindingDao.findActiveByClientAndPersona(clientId, persona.getPersonaCode());
        if (bound != null) {
            if (jiacn.equals(bound.getJiacn())) {
                AgentRuntimeEntity existing = agentRuntimeDao.findByAgentId(bound.getAgentId());
                return existing == null ? createRuntimeFromBinding(bound, persona, AgentConstants.STATUS_OFFLINE) : toRuntimeDTO(existing);
            }
            throw new AgentBizException(AgentErrorConstants.PERSONA_BOUND, "Persona has been bound in this client");
        }

        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setClientId(clientId);
        binding.setJiacn(jiacn);
        binding.setPersonaCode(persona.getPersonaCode());
        binding.setAgentId(generateAgentId(clientId, persona.getPersonaCode()));
        binding.setBoundAt(System.currentTimeMillis());
        binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        agentPersonaBindingDao.insert(binding);
        return createRuntimeFromBinding(binding, persona, AgentConstants.STATUS_OFFLINE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentPersonaBindResultDTO bindPersona(String personaCode, String mode) {
        AgentRuntimeDTO agent = bindPersona(personaCode);
        AgentPersonaEntity persona = requirePersona(personaCode);
        String normalizedMode = StringUtil.isBlank(mode) ? BIND_MODE_LOCAL : mode.trim().toLowerCase();
        if (BIND_MODE_SERVER.equals(normalizedMode)) {
            return buildServerHostedBinding(agent, persona);
        }
        if (BIND_MODE_LOCAL.equals(normalizedMode)) {
            return buildLocalBindingGuide(agent, persona);
        }
        throw new IllegalArgumentException("Unsupported bind mode: " + mode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindPersona(String personaCode) {
        String clientId = resolveCurrentClientId();
        String jiacn = resolveCurrentJiacn();
        AgentPersonaEntity persona = requirePersona(personaCode);
        if (Boolean.TRUE.equals(persona.getSystemAgent())) {
            throw new AgentBizException(AgentErrorConstants.PERSONA_NOT_BINDABLE, "System persona cannot be unbound");
        }
        AgentPersonaBindingEntity binding = agentPersonaBindingDao.findActiveByClientJiacnAndPersona(clientId, jiacn, persona.getPersonaCode());
        if (binding == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Persona is not bound to current user");
        }
        binding.setStatus(AgentConstants.BINDING_STATUS_INACTIVE);
        agentPersonaBindingDao.updateById(binding);
        disableServerHostedProfile(persona);
        AgentRuntimeEntity runtime = agentRuntimeDao.findByAgentId(binding.getAgentId());
        if (runtime != null) {
            runtime.setStatus(AgentConstants.STATUS_OFFLINE);
            runtime.setLastSeenAt(System.currentTimeMillis());
            agentRuntimeDao.updateById(runtime);
            publishAgentStatus(toRuntimeDTO(runtime));
        }
    }

    @Override
    public AgentRuntimeDTO requireApiKeyOwnedAgent(String clientId, String jiacn, String agentId) {
        if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agentId)) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "System agent cannot be registered by external clients");
        }
        AgentRuntimeEntity agent = requireAgent(agentId);
        if (!clientId.equals(agent.getClientId()) || !jiacn.equals(agent.getOwnerJiacn())) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Agent is not owned by the API key user");
        }
        AgentPersonaBindingEntity binding = agentPersonaBindingDao.findActiveByClientJiacnAndAgentId(clientId, jiacn, agentId);
        if (binding == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Agent binding is inactive or missing");
        }
        return toRuntimeDTO(agent);
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
        AgentRuntimeEntity entity = requireAgent(agentId);
        requireOwnedAgent(entity);
        if (!StringUtil.isBlank(request.getStatus())) {
            validateAgentStatus(request.getStatus());
            entity.setStatus(request.getStatus());
        }
        entity.setCurrentTaskId(request.getCurrentTaskId());
        entity.setCurrentTaskTitle(request.getCurrentTaskTitle());
        entity.setErrorMessage(request.getErrorMessage());
        entity.setLastSeenAt(System.currentTimeMillis());
        agentRuntimeDao.updateById(entity);
        AgentRuntimeDTO dto = toRuntimeDTO(entity);
        publishAgentStatus(dto);
        return dto;
    }

    @Override
    public List<AgentTaskDTO> getAgentTasks(String agentId) {
        requireOwnedAgent(requireAgent(agentId));
        return agentTaskMetaDao.findByAgentId(agentId).stream().map(this::toTaskDTO).toList();
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
        int pageNum = Optional.ofNullable(request.getPageNum()).orElse(1);
        int pageSize = Optional.ofNullable(request.getPageSize()).orElse(20);
        String keyword = request.getKeyword();
        List<AgentTaskDTO> tasks = agentTaskMetaDao.search(request.getStatus(), request.getAbility())
                .stream()
                .map(this::toTaskDTO)
                .filter(task -> matchesTaskKeyword(task, keyword))
                .toList();
        return pageTasks(tasks, pageNum, pageSize);
    }

    @Override
    public Map<String, Long> countTasksByStatus(AgentTaskSearchDTO request) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", 0L);
        List.of(AgentConstants.TASK_STATUS_OPEN, AgentConstants.TASK_STATUS_ASSIGNED,
                AgentConstants.TASK_STATUS_RUNNING, AgentConstants.TASK_STATUS_COMPLETED,
                AgentConstants.TASK_STATUS_FAILED, AgentConstants.TASK_STATUS_ARCHIVED)
                .forEach(status -> counts.put(status, 0L));

        String ability = request == null ? null : request.getAbility();
        String keyword = request == null ? null : request.getKeyword();
        agentTaskMetaDao.search(null, ability).stream()
                .filter(task -> StringUtil.isBlank(keyword) || String.valueOf(task.getTaskId()).contains(keyword))
                .forEach(task -> {
                    String status = Optional.ofNullable(task.getRewardStatus()).orElse(AgentConstants.TASK_STATUS_OPEN);
                    counts.put("total", counts.get("total") + 1);
                    counts.put(status, counts.getOrDefault(status, 0L) + 1);
                });
        return counts;
    }

    private boolean matchesTaskKeyword(AgentTaskDTO task, String keyword) {
        if (StringUtil.isBlank(keyword)) {
            return true;
        }
        String normalizedKeyword = keyword.trim().toLowerCase();
        return containsIgnoreCase(task.getId(), normalizedKeyword)
                || containsIgnoreCase(task.getTitle(), normalizedKeyword)
                || containsIgnoreCase(task.getDescription(), normalizedKeyword)
                || containsIgnoreCase(task.getAssignedAgentName(), normalizedKeyword)
                || task.getRequiredAbilities().stream().anyMatch(ability -> containsIgnoreCase(ability, normalizedKeyword));
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase().contains(normalizedKeyword);
    }

    private PageInfo<AgentTaskDTO> pageTasks(List<AgentTaskDTO> tasks, int pageNum, int pageSize) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        int fromIndex = Math.min((safePageNum - 1) * safePageSize, tasks.size());
        int toIndex = Math.min(fromIndex + safePageSize, tasks.size());
        PageInfo<AgentTaskDTO> pageInfo = PageInfo.of(tasks.subList(fromIndex, toIndex));
        pageInfo.setPageNum(safePageNum);
        pageInfo.setPageSize(safePageSize);
        pageInfo.setTotal(tasks.size());
        return pageInfo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskDTO createTask(AgentTaskCreateDTO request) {
        require(!StringUtil.isBlank(request.getTitle()), "title is required");

        String taskId = UUID.randomUUID().toString();
        TaskService taskService = taskServiceProvider.getIfAvailable();
        if (taskService != null) {
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
            taskService.create(taskPlan);
            if (taskPlan.getId() != null) {
                taskId = String.valueOf(taskPlan.getId());
            }
        }

        AgentTaskMetaEntity meta = new AgentTaskMetaEntity();
        meta.setTaskId(taskId);
        meta.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        meta.setRequiredAbilities(JsonUtil.toJson(Optional.ofNullable(request.getRequiredAbilities()).orElseGet(Collections::emptyList)));
        meta.setReward(request.getReward());
        saveMeta(meta);

        AgentTaskDTO task = toTaskDTO(meta);
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        publishTaskEvent("task_created", task);
        return task;
    }

    @Override
    public AgentTaskDTO getTask(String taskId) {
        return toTaskDTO(requireTask(taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskDTO assignTask(String taskId, AgentTaskAssignDTO request) {
        List<String> agentIds = normalizeAssignAgentIds(request);
        require(!agentIds.isEmpty(), "agentId is required");
        List<AgentRuntimeEntity> assignedAgents = agentIds.stream()
                .map(this::requireAgent)
                .toList();
        assignedAgents.forEach(this::requireOwnedAgent);
        boolean allowQueue = Boolean.TRUE.equals(request.getAllowQueue());
        assignedAgents.forEach(agent -> validateAssignableAgent(agent, allowQueue));

        AgentTaskMetaEntity meta = Optional.ofNullable(agentTaskMetaDao.findByTaskId(taskId)).orElseGet(() -> {
            AgentTaskMetaEntity created = new AgentTaskMetaEntity();
            created.setTaskId(taskId);
            created.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
            return created;
        });
        assignedAgents.forEach(agent -> validateAbility(agent, meta));
        meta.setAssignedAgentId(agentIds.size() == 1 ? agentIds.getFirst() : JsonUtil.toJson(agentIds));
        meta.setRewardStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        meta.setAssignedAt(System.currentTimeMillis());
        saveMeta(meta);

        AgentTaskDTO task = toTaskDTO(meta);
        task.setActionDispatchResults(dispatchTaskAssignedActions(task, assignedAgents));
        publishTaskEvent("task_assigned", task);
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
        AgentTaskDTO task = getTask(taskId);
        List<AgentTaskRecommendationDTO> recommendations = recommendTaskAssignees(taskId);
        List<String> selectedAgentIds = selectAutoAssignAgentIds(task, recommendations);
        require(!selectedAgentIds.isEmpty(), "No available agent can accept this task");

        AgentTaskAssignDTO assignRequest = new AgentTaskAssignDTO();
        assignRequest.setAgentIds(selectedAgentIds);
        assignRequest.setAllowQueue(request != null && Boolean.TRUE.equals(request.getAllowQueue()));
        return assignTask(taskId, assignRequest);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskDTO reportTask(String taskId, AgentTaskReportDTO request) {
        AgentTaskMetaEntity meta = Optional.ofNullable(agentTaskMetaDao.findByTaskId(taskId)).orElseThrow(() ->
                new AgentBizException(AgentErrorConstants.TASK_NOT_FOUND, "Task not found"));
        if (!StringUtil.isBlank(request.getStatus())) {
            validateTaskStatus(request.getStatus());
            meta.setRewardStatus(request.getStatus());
        }
        if (AgentConstants.TASK_STATUS_RUNNING.equals(meta.getRewardStatus())) {
            meta.setStartedAt(System.currentTimeMillis());
        }
        if (AgentConstants.TASK_STATUS_COMPLETED.equals(meta.getRewardStatus())) {
            meta.setCompletedAt(System.currentTimeMillis());
        }
        if (AgentConstants.TASK_STATUS_FAILED.equals(meta.getRewardStatus())) {
            meta.setFailureReason(request.getFailureReason());
        }
        saveMeta(meta);

        for (String assignedAgentId : parseAssignedAgentIds(meta.getAssignedAgentId())) {
            AgentStatusDTO status = new AgentStatusDTO();
            status.setStatus(resolveAgentStatus(meta.getRewardStatus()));
            status.setCurrentTaskId(AgentConstants.TASK_STATUS_COMPLETED.equals(meta.getRewardStatus()) ? null : meta.getTaskId());
            status.setCurrentTaskTitle(request.getCurrentTaskTitle());
            status.setErrorMessage(request.getFailureReason());
            updateStatus(assignedAgentId, status);
        }

        AgentTaskDTO task = toTaskDTO(meta);
        publishTaskEvent("task_" + meta.getRewardStatus(), task);
        return task;
    }

    @Override
    public AgentTaskNoteDTO addTaskNote(String taskId, AgentTaskNoteDTO request) {
        requireTask(taskId);
        require(request != null && !StringUtil.isBlank(request.getContent()), "note content is required");
        AgentTaskNoteEntity note = new AgentTaskNoteEntity();
        note.setTaskId(taskId);
        note.setAuthorId(request.getAuthorId());
        note.setAuthorType(StringUtil.isBlank(request.getAuthorType()) ? "user" : request.getAuthorType());
        note.setNoteType(StringUtil.isBlank(request.getNoteType()) ? "summary" : request.getNoteType());
        note.setContent(request.getContent());
        note.setCreatedAt(System.currentTimeMillis());
        agentTaskNoteDao.insert(note);
        return toTaskNoteDTO(note);
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
        AgentTaskMetaEntity meta = requireTask(taskId);
        meta.setRewardStatus(AgentConstants.TASK_STATUS_ARCHIVED);
        saveMeta(meta);
        AgentTaskDTO task = toTaskDTO(meta);
        publishTaskEvent("task_archived", task);
        return task;
    }

    private void saveMeta(AgentTaskMetaEntity meta) {
        if (meta.getId() == null) {
            agentTaskMetaDao.insert(meta);
        } else {
            agentTaskMetaDao.updateById(meta);
        }
    }

    private AgentTaskMetaEntity requireTask(String taskId) {
        return Optional.ofNullable(agentTaskMetaDao.findByTaskId(taskId)).orElseThrow(() ->
                new AgentBizException(AgentErrorConstants.TASK_NOT_FOUND, "Task not found"));
    }

    private List<String> normalizeAssignAgentIds(AgentTaskAssignDTO request) {
        List<String> agentIds = Optional.ofNullable(request.getAgentIds()).orElseGet(Collections::emptyList);
        if (agentIds.isEmpty() && !StringUtil.isBlank(request.getAgentId())) {
            agentIds = List.of(request.getAgentId());
        }
        return new ArrayList<>(new LinkedHashSet<>(agentIds.stream()
                .filter(agentId -> !StringUtil.isBlank(agentId))
                .toList()));
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
        List<String> abilities = parseList(agent.getAbilities());
        boolean matched = required.stream().anyMatch(abilities::contains);
        if (!matched) {
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
        if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agent.getAgentId())) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "System agent cannot be operated directly");
        }
        String clientId = resolveCurrentClientId();
        String jiacn = resolveCurrentJiacn();
        if (!Objects.equals(clientId, agent.getClientId()) || !Objects.equals(jiacn, agent.getOwnerJiacn())) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Agent is not bound to current user");
        }
        AgentPersonaBindingEntity binding = agentPersonaBindingDao.findActiveByClientJiacnAndAgentId(clientId, jiacn, agent.getAgentId());
        if (binding == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Agent binding is inactive or missing");
        }
    }

    private AgentRuntimeDTO createRuntimeFromBinding(AgentPersonaBindingEntity binding, AgentPersonaEntity persona, String status) {
        AgentRuntimeEntity runtime = Optional.ofNullable(agentRuntimeDao.findByAgentId(binding.getAgentId()))
                .orElseGet(AgentRuntimeEntity::new);
        runtime.setAgentId(binding.getAgentId());
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
        } else {
            agentRuntimeDao.updateById(runtime);
        }
        return toRuntimeDTO(runtime);
    }

    private AgentPersonaBindResultDTO buildServerHostedBinding(AgentRuntimeDTO agent, AgentPersonaEntity persona) {
        String profileId = safeProfileId(persona.getPersonaCode());
        Path workdir = AGENT_CLIENTS_DIR.resolve(safePathName(agent.getAgentId()));
        Path codexHome = CODEX_WS_AGENT_DIR.resolve(".codex-" + profileId);
        ServerProfileWriteResult writeResult;
        String profileApiKey;
        try {
            Files.createDirectories(workdir);
            Files.createDirectories(codexHome);
            copyCodexBootstrapFile("config.toml", codexHome);
            copyCodexBootstrapFile("auth.json", codexHome);
            if (!Files.exists(CODEX_WS_PROFILES_FILE)) {
                Files.createDirectories(CODEX_WS_PROFILES_FILE.getParent());
                Files.writeString(CODEX_WS_PROFILES_FILE, defaultProfileSection(), StandardCharsets.UTF_8);
            }
            String profiles = Files.readString(CODEX_WS_PROFILES_FILE, StandardCharsets.UTF_8);
            profileApiKey = ensureAgentApiKey();
            String section = serverProfileSection(profileId, agent, persona, workdir, codexHome, true, profileApiKey);
            writeResult = upsertServerProfileSection(profiles, profileId, section);
            if (writeResult.changed) {
                Files.writeString(CODEX_WS_PROFILES_FILE, writeResult.profiles, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new AgentBizException(AgentErrorConstants.AGENT_ERROR, "Failed to provision server agent: " + e.getMessage());
        }

        AgentPersonaBindResultDTO result = baseBindResult(agent, BIND_MODE_SERVER, profileId, workdir, codexHome);
        result.setApiKey(profileApiKey);
        result.setProfilesFile(CODEX_WS_PROFILES_FILE.toString());
        result.setServerProfileAlreadyExists(writeResult.existed);
        result.setServerProfileCreated(!writeResult.existed);
        result.setMessage(!writeResult.existed
                ? "已创建服务器代管 profile，codex-ws-agent 会自动检测后上线"
                : "服务器代管 profile 已启用，codex-ws-agent 会自动检测配置变化");
        result.setCommands(List.of(
                "tail -f /home/isp/apps/codex-ws-agent/logs/startlog_*.log",
                "journalctl -u codex-ws-agent -f"
        ));
        return result;
    }

    private void disableServerHostedProfile(AgentPersonaEntity persona) {
        String profileId = safeProfileId(persona.getPersonaCode());
        if (!Files.exists(CODEX_WS_PROFILES_FILE)) {
            return;
        }
        try {
            String profiles = Files.readString(CODEX_WS_PROFILES_FILE, StandardCharsets.UTF_8);
            ServerProfileRange range = findServerProfileRange(profiles, profileId);
            if (range == null) {
                return;
            }
            String section = profiles.substring(range.start, range.end);
            String disabledSection = setProfileEnabled(section, false);
            if (!section.equals(disabledSection)) {
                Files.writeString(CODEX_WS_PROFILES_FILE,
                        profiles.substring(0, range.start) + disabledSection + profiles.substring(range.end),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new AgentBizException(AgentErrorConstants.AGENT_ERROR, "Failed to disable server agent: " + e.getMessage());
        }
    }

    private String serverProfileSection(String profileId, AgentRuntimeDTO agent, AgentPersonaEntity persona,
            Path workdir, Path codexHome, boolean enabled, String apiKey) {
        return """

[agent.%s]
agentId=%s
codexWorkdir=%s
agentName=%s
personaName=%s
codexHome=%s
enabled=%s
apiKey=%s
""".formatted(profileId, agent.getAgentId(), workdir, persona.getName(), personaDisplayTitle(persona), codexHome, enabled, apiKey);
    }

    private String ensureAgentApiKey() {
        ApiKeyService apiKeyService = apiKeyServiceProvider.getIfAvailable();
        if (apiKeyService == null) {
            throw new AgentBizException(AgentErrorConstants.AGENT_ERROR, "ApiKeyService is unavailable");
        }
        String clientId = resolveCurrentClientId();
        String jiacn = resolveCurrentJiacn();
        long now = System.currentTimeMillis();

        OauthApiKeyEntity query = new OauthApiKeyEntity();
        query.setClientId(clientId);
        query.setJiacn(jiacn);
        return apiKeyService.findList(query).stream()
                .filter(key -> key.getStatus() == null || key.getStatus() == 1)
                .filter(key -> key.getExpireTime() == null || key.getExpireTime() > now)
                .filter(key -> !StringUtil.isBlank(key.getApiKey()))
                .findFirst()
                .map(OauthApiKeyEntity::getApiKey)
                .orElseGet(() -> createAgentApiKey(apiKeyService, clientId, jiacn));
    }

    private String createAgentApiKey(ApiKeyService apiKeyService, String clientId, String jiacn) {
        OauthApiKeyEntity apiKey = new OauthApiKeyEntity();
        apiKey.setClientId(clientId);
        apiKey.setJiacn(jiacn);
        apiKey.setApiKey("cdx_" + UUID.randomUUID().toString().replace("-", ""));
        apiKey.setKeyName("codex-ws-agent");
        apiKey.setStatus(1);
        apiKey.setDescription("Auto-created for Codex WebSocket agent profile binding");
        OauthApiKeyEntity saved = apiKeyService.create(apiKey);
        if (saved == null || StringUtil.isBlank(saved.getApiKey())) {
            throw new AgentBizException(AgentErrorConstants.AGENT_ERROR, "Failed to create Codex agent API key");
        }
        return saved.getApiKey();
    }

    private ServerProfileWriteResult upsertServerProfileSection(String profiles, String profileId, String section) {
        ServerProfileRange range = findServerProfileRange(profiles, profileId);
        if (range == null) {
            String nextProfiles = profiles.stripTrailing() + section;
            return new ServerProfileWriteResult(false, true, nextProfiles);
        }
        String nextProfiles = profiles.substring(0, range.start) + section + profiles.substring(range.end);
        return new ServerProfileWriteResult(true, !profiles.equals(nextProfiles), nextProfiles);
    }

    private ServerProfileRange findServerProfileRange(String profiles, String profileId) {
        String header = "[agent." + profileId + "]";
        int headerStart = profiles.indexOf(header);
        if (headerStart < 0) {
            return null;
        }
        int sectionStart = headerStart;
        while (sectionStart > 0 && profiles.charAt(sectionStart - 1) != '\n') {
            sectionStart--;
        }
        int nextAgent = profiles.indexOf("\n[agent.", headerStart + header.length());
        int nextProfile = profiles.indexOf("\n[profile.", headerStart + header.length());
        int sectionEnd = Stream.of(nextAgent, nextProfile)
                .filter(index -> index >= 0)
                .min(Integer::compareTo)
                .orElse(profiles.length());
        return new ServerProfileRange(sectionStart, sectionEnd);
    }

    private String setProfileEnabled(String section, boolean enabled) {
        String value = "enabled=" + enabled;
        if (section.lines().anyMatch(line -> line.trim().startsWith("enabled="))) {
            return section.replaceAll("(?m)^\\s*enabled\\s*=.*$", value);
        }
        String suffix = section.endsWith("\n") ? "" : "\n";
        return section + suffix + value + "\n";
    }

    private static class ServerProfileRange {
        private final int start;
        private final int end;

        private ServerProfileRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class ServerProfileWriteResult {
        private final boolean existed;
        private final boolean changed;
        private final String profiles;

        private ServerProfileWriteResult(boolean existed, boolean changed, String profiles) {
            this.existed = existed;
            this.changed = changed;
            this.profiles = profiles;
        }
    }

    private AgentPersonaBindResultDTO buildLocalBindingGuide(AgentRuntimeDTO agent, AgentPersonaEntity persona) {
        String profileId = safeProfileId(persona.getPersonaCode());
        Path workdir = Path.of("$HOME/cyf-agent-clients").resolve(safePathName(agent.getAgentId()));
        Path codexHome = Path.of("$HOME/.codex-" + profileId);
        AgentPersonaBindResultDTO result = baseBindResult(agent, BIND_MODE_LOCAL, profileId, workdir, codexHome);
        String apiKey = ensureAgentApiKey();
        String wsUrl = Optional.ofNullable(System.getenv("WS_URL"))
                .filter(value -> !StringUtil.isBlank(value))
                .orElse("ws://<当前服务地址>:10018/ws/agent/channel");
        result.setApiKey(apiKey);
        result.setMessage("已入名册，请在本机安装 codex-ws-agent 并用下列配置连接");
        result.setEnvExample("""
WS_URL=%s
OPENCLAW_API_KEY=%s
DEFAULT_CODEX_PROFILE=%s
CODEX_PROFILES_FILE=$PWD/codex-profiles.conf
HEARTBEAT_MS=30000
RECONNECT_MAX_MS=1800000
""".formatted(wsUrl, apiKey, profileId).stripTrailing());
        result.setProfileExample((defaultProfileSection() + """

[agent.%s]
agentId=%s
codexWorkdir=%s
agentName=%s
personaName=%s
codexHome=%s
isDefault=true
""").formatted(profileId, agent.getAgentId(), workdir, persona.getName(), personaDisplayTitle(persona), codexHome).stripTrailing());
        result.setCommands(List.of(
                "mkdir -p ~/apps/codex-ws-agent && cd ~/apps/codex-ws-agent",
                "cp /path/to/agent-client.mjs /path/to/package.json .",
                "npm install",
                "codex login",
                "node agent-client.mjs"
        ));
        return result;
    }

    private AgentPersonaBindResultDTO baseBindResult(AgentRuntimeDTO agent, String mode, String profileId, Path workdir, Path codexHome) {
        AgentPersonaBindResultDTO result = new AgentPersonaBindResultDTO();
        result.setAgent(agent);
        result.setMode(mode);
        result.setAgentId(agent.getAgentId());
        result.setProfileId(profileId);
        result.setWorkdir(workdir.toString());
        result.setCodexHome(codexHome.toString());
        result.setServerProfileCreated(false);
        result.setServerProfileAlreadyExists(false);
        return result;
    }

    private void copyCodexBootstrapFile(String filename, Path codexHome) throws IOException {
        Path source = CODEX_WS_AGENT_DIR.resolve(".codex").resolve(filename);
        Path target = codexHome.resolve(filename);
        if (Files.exists(source) && !Files.exists(target)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private String defaultProfileSection() {
        return """
[default]
codexBin=/usr/local/bin/codex
codexWorkdir=/home/isp
codexSandbox=workspace-write
codexApproval=never
codexSessionMode=resume
codexTimeoutMs=900000
""".stripTrailing();
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
        dto.setBoundToMe(Objects.equals(resolveCurrentClientId(), entity.getClientId())
                && Objects.equals(resolveCurrentJiacn(), entity.getOwnerJiacn()));
        dto.setCanBind(false);
        dto.setCanOperate(Boolean.TRUE.equals(dto.getBoundToMe()) && !Boolean.TRUE.equals(dto.getSystemAgent()));
        dto.setStats(buildStats(entity));
        return dto;
    }

    private AgentRuntimeDTO toCatalogDTO(AgentPersonaEntity persona, String clientId, String jiacn) {
        AgentPersonaBindingEntity binding = Boolean.TRUE.equals(persona.getSystemAgent())
                ? null
                : agentPersonaBindingDao.findActiveByClientAndPersona(clientId, persona.getPersonaCode());
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(binding == null ? generateAgentId(clientId, persona.getPersonaCode()) : binding.getAgentId());
        dto.setName(persona.getName());
        dto.setAvatar(persona.getAvatar());
        dto.setPersonaCode(persona.getPersonaCode());
        dto.setPersonaName(persona.getName());
        dto.setTitle(persona.getTitle());
        dto.setStarName(persona.getStarName());
        dto.setRankNo(persona.getRankNo());
        dto.setVisualConfig(persona.getVisualConfig());
        dto.setSystemAgent(Boolean.TRUE.equals(persona.getSystemAgent()));
        dto.setAbilities(parseList(persona.getAbilities()));
        dto.setStatus(binding == null ? AgentConstants.STATUS_OFFLINE
                : Optional.ofNullable(agentRuntimeDao.findByAgentId(binding.getAgentId()))
                        .map(AgentRuntimeEntity::getStatus)
                        .orElse(AgentConstants.STATUS_OFFLINE));
        dto.setOwnerJiacn(binding == null ? null : binding.getJiacn());
        dto.setBound(binding != null || Boolean.TRUE.equals(persona.getSystemAgent()));
        dto.setBoundToMe(binding != null && jiacn.equals(binding.getJiacn()));
        dto.setCanBind(!Boolean.TRUE.equals(persona.getSystemAgent()) && binding == null);
        dto.setCanOperate(Boolean.TRUE.equals(dto.getBoundToMe()));
        AgentRuntimeEntity statsEntity = new AgentRuntimeEntity();
        statsEntity.setAgentId(dto.getAgentId());
        statsEntity.setPersonaCode(persona.getPersonaCode());
        statsEntity.setPersonaName(persona.getName());
        statsEntity.setAbilities(persona.getAbilities());
        dto.setStats(buildStats(statsEntity));
        return dto;
    }

    private AgentRuntimeDTO buildSongjiangDTO() {
        AgentPersonaEntity persona = agentPersonaDao.findByCode(AgentConstants.BUILTIN_SONGJIANG_PERSONA_CODE);
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
            dto.setStats(buildStats(statsEntity));
        } else {
            dto.setName("宋江");
            dto.setPersonaName("宋江");
            dto.setTitle("及时雨");
            dto.setAbilities(List.of("coordination", "dispatch", "planning", "briefing"));
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

    private AgentStatsDTO buildStats(AgentRuntimeEntity entity) {
        AgentStatsDTO stats = new AgentStatsDTO();
        AgentPersonaEntity persona = resolvePersona(entity);
        if (persona != null) {
            stats.setPower(persona.getPower());
            stats.setIntelligence(persona.getIntelligence());
            stats.setLeadership(persona.getLeadership());
        }
        List<AgentTaskMetaEntity> tasks = Optional.ofNullable(agentTaskMetaDao.findByAgentId(entity.getAgentId()))
                .orElseGet(Collections::emptyList);
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

        Set<String> remaining = required.stream().map(String::toLowerCase).collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<String> selected = new ArrayList<>();
        for (AgentTaskRecommendationDTO recommendation : recommendations) {
            if (!isOnlineRecommendation(recommendation)) {
                continue;
            }
            boolean contributes = recommendation.getMatchedAbilities().stream()
                    .map(String::toLowerCase)
                    .anyMatch(remaining::contains);
            if (!contributes && !selected.isEmpty()) {
                continue;
            }
            selected.add(recommendation.getAgent().getAgentId());
            recommendation.getMatchedAbilities().stream()
                    .map(String::toLowerCase)
                    .forEach(remaining::remove);
            if (remaining.isEmpty() || selected.size() >= 3) {
                break;
            }
        }
        if (selected.isEmpty()) {
            return recommendations.stream()
                    .filter(this::isOnlineRecommendation)
                    .findFirst()
                    .map(recommendation -> List.of(recommendation.getAgent().getAgentId()))
                    .orElseGet(List::of);
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
                .map(String::toLowerCase)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        return requiredAbilities.stream()
                .filter(ability -> agentAbilitySet.contains(String.valueOf(ability).toLowerCase()))
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
                .map(String::toLowerCase)
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

    private AgentTaskDTO toTaskDTO(AgentTaskMetaEntity meta) {
        AgentTaskDTO dto = new AgentTaskDTO();
        dto.setId(meta.getTaskId());
        dto.setTitle(meta.getTaskId());
        dto.setStatus(meta.getRewardStatus());
        dto.setRequiredAbilities(parseList(meta.getRequiredAbilities()));
        dto.setReward(meta.getReward());
        dto.setAssignedAgentId(meta.getAssignedAgentId());
        List<String> assignedAgentIds = parseAssignedAgentIds(meta.getAssignedAgentId());
        dto.setAssignedAgentIds(assignedAgentIds);
        dto.setAssignees(assignedAgentIds.stream().map(this::toAssigneeDTO).toList());
        if (!assignedAgentIds.isEmpty()) {
            dto.setAssignedAgentId(assignedAgentIds.getFirst());
            dto.setAssignedAgentName(dto.getAssignees().isEmpty() ? null : dto.getAssignees().getFirst().getAgentName());
        }
        dto.setCreatedAt(meta.getCreateTime());
        dto.setUpdatedAt(meta.getUpdateTime());
        dto.setAssignedAt(meta.getAssignedAt());
        dto.setStartedAt(meta.getStartedAt());
        dto.setCompletedAt(meta.getCompletedAt());
        dto.setFailureReason(meta.getFailureReason());
        enrichTaskPlan(dto, meta.getTaskId());
        return dto;
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
        if (StringUtil.isBlank(json)) {
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

    private void publishAgentStatus(AgentRuntimeDTO agent) {
        Optional.ofNullable(eventPublisherProvider.getIfAvailable()).ifPresent(publisher -> {
            publisher.publishAgentStatus(agent);
            publisher.publishCapabilityIndex(listCapabilities());
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
        context.put("acceptance", "回报执行计划、风险、需要协助的对象，完成后提交 task.report。");
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

    private String generateAgentId(String clientId, String personaCode) {
        String safeClientId = Optional.ofNullable(clientId).orElse("jia_client")
                .replaceAll("[^A-Za-z0-9_-]", "-");
        return "jyt-" + safeClientId + "-" + personaCode;
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
