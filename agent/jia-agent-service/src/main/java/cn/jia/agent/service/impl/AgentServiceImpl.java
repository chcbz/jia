package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
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
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.DialogueRequestDTO;
import cn.jia.agent.entity.DialogueTemplateEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentServiceImpl implements AgentService {
    private final AgentRuntimeDao agentRuntimeDao;
    private final AgentPersonaDao agentPersonaDao;
    private final AgentPersonaBindingDao agentPersonaBindingDao;
    private final AgentTaskMetaDao agentTaskMetaDao;
    private final AgentTaskNoteDao agentTaskNoteDao;
    private final DialogueTemplateDao dialogueTemplateDao;
    private final ObjectProvider<AgentEventPublisher> eventPublisherProvider;
    private final ObjectProvider<TaskService> taskServiceProvider;

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
        PageHelper.startPage(pageNum, pageSize);
        List<AgentTaskDTO> tasks = agentTaskMetaDao.search(request.getStatus(), request.getAbility())
                .stream()
                .filter(task -> StringUtil.isBlank(request.getKeyword()) || String.valueOf(task.getTaskId()).contains(request.getKeyword()))
                .map(this::toTaskDTO)
                .toList();
        return PageInfo.of(tasks);
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
        Optional.ofNullable(eventPublisherProvider.getIfAvailable()).ifPresent(publisher -> publisher.publishAgentStatus(agent));
    }

    private void publishTaskEvent(String eventType, AgentTaskDTO task) {
        Optional.ofNullable(eventPublisherProvider.getIfAvailable()).ifPresent(publisher -> publisher.publishTaskEvent(eventType, task));
    }

    private List<AgentActionDispatchResultDTO> dispatchTaskAssignedActions(AgentTaskDTO task, List<AgentRuntimeEntity> assignedAgents) {
        AgentEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        return assignedAgents.stream()
                .map(agent -> dispatchTaskAssignedAction(task, agent, publisher))
                .toList();
    }

    private AgentActionDispatchResultDTO dispatchTaskAssignedAction(AgentTaskDTO task, AgentRuntimeEntity agent, AgentEventPublisher publisher) {
        AgentActionIntentDTO intent = buildTaskBriefingIntent(task, agent);
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

    private AgentActionIntentDTO buildTaskBriefingIntent(AgentTaskDTO task, AgentRuntimeEntity agent) {
        AgentActionIntentDTO intent = new AgentActionIntentDTO();
        intent.setIntentId(UUID.randomUUID().toString());
        intent.setActionType("task_briefing");
        intent.setActorAgentId(agent.getAgentId());
        intent.setTargetAgentIds(List.of(agent.getAgentId()));
        intent.setTaskId(task.getId());
        intent.setReason("Bounty task assigned; the agent should review the task and prepare a response.");
        intent.setInstruction("Read the bounty task, confirm whether it can be accepted, and report the next plan.");
        intent.setAutonomyLevel("assist");
        intent.setRequiresApproval(false);
        intent.setConversationType("juyiting");
        intent.setCreatedAt(System.currentTimeMillis());
        return intent;
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
