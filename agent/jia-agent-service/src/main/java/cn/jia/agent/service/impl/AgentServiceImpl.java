package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentStatsDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.DialogueRequestDTO;
import cn.jia.agent.entity.DialogueTemplateEntity;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentService;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {
    private final AgentRuntimeDao agentRuntimeDao;
    private final AgentPersonaDao agentPersonaDao;
    private final AgentTaskMetaDao agentTaskMetaDao;
    private final DialogueTemplateDao dialogueTemplateDao;
    private final ObjectProvider<AgentEventPublisher> eventPublisherProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRegisterResultDTO register(AgentRegisterDTO request) {
        require(!StringUtil.isBlank(request.getAgentId()), "agentId is required");
        require(!StringUtil.isBlank(request.getName()), "name is required");

        String token = UUID.randomUUID().toString().replace("-", "");
        AgentRuntimeEntity entity = Optional.ofNullable(agentRuntimeDao.findByAgentId(request.getAgentId()))
                .orElseGet(AgentRuntimeEntity::new);
        entity.setAgentId(request.getAgentId());
        entity.setName(request.getName());
        entity.setAvatar(request.getAvatar());
        entity.setPersonaName(request.getPersonaName());
        entity.setAbilities(JsonUtil.toJson(Optional.ofNullable(request.getAbilities()).orElseGet(Collections::emptyList)));
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
    public AgentRuntimeDTO get(String agentId) {
        return toRuntimeDTO(requireAgent(agentId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRuntimeDTO updateStatus(String agentId, AgentStatusDTO request) {
        AgentRuntimeEntity entity = requireAgent(agentId);
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
        requireAgent(agentId);
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
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskDTO assignTask(String taskId, AgentTaskAssignDTO request) {
        AgentRuntimeEntity agent = requireAgent(request.getAgentId());
        validateAssignableAgent(agent, Boolean.TRUE.equals(request.getAllowQueue()));

        AgentTaskMetaEntity meta = Optional.ofNullable(agentTaskMetaDao.findByTaskId(taskId)).orElseGet(() -> {
            AgentTaskMetaEntity created = new AgentTaskMetaEntity();
            created.setTaskId(taskId);
            created.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
            return created;
        });
        validateAbility(agent, meta);
        meta.setAssignedAgentId(agent.getAgentId());
        meta.setRewardStatus(AgentConstants.TASK_STATUS_ASSIGNED);
        meta.setAssignedAt(System.currentTimeMillis());
        saveMeta(meta);

        AgentTaskDTO task = toTaskDTO(meta);
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

        if (!StringUtil.isBlank(meta.getAssignedAgentId())) {
            AgentStatusDTO status = new AgentStatusDTO();
            status.setStatus(resolveAgentStatus(meta.getRewardStatus()));
            status.setCurrentTaskId(AgentConstants.TASK_STATUS_COMPLETED.equals(meta.getRewardStatus()) ? null : meta.getTaskId());
            status.setCurrentTaskTitle(request.getCurrentTaskTitle());
            status.setErrorMessage(request.getFailureReason());
            updateStatus(meta.getAssignedAgentId(), status);
        }

        AgentTaskDTO task = toTaskDTO(meta);
        publishTaskEvent("task_" + meta.getRewardStatus(), task);
        return task;
    }

    private void saveMeta(AgentTaskMetaEntity meta) {
        if (meta.getId() == null) {
            agentTaskMetaDao.insert(meta);
        } else {
            agentTaskMetaDao.updateById(meta);
        }
    }

    private void validateAssignableAgent(AgentRuntimeEntity agent, boolean allowQueue) {
        if (AgentConstants.STATUS_OFFLINE.equals(agent.getStatus())) {
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

    private AgentRuntimeDTO toRuntimeDTO(AgentRuntimeEntity entity) {
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(entity.getAgentId());
        dto.setName(entity.getName());
        dto.setAvatar(entity.getAvatar());
        dto.setPersonaName(entity.getPersonaName());
        dto.setAbilities(parseList(entity.getAbilities()));
        dto.setStatus(entity.getStatus());
        dto.setEndpoint(entity.getEndpoint());
        dto.setCurrentTaskId(entity.getCurrentTaskId());
        dto.setCurrentTaskTitle(entity.getCurrentTaskTitle());
        dto.setLastSeenAt(entity.getLastSeenAt());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setStats(buildStats(entity));
        return dto;
    }

    private AgentStatsDTO buildStats(AgentRuntimeEntity entity) {
        AgentStatsDTO stats = new AgentStatsDTO();
        AgentPersonaEntity persona = StringUtil.isBlank(entity.getPersonaName()) ? null : agentPersonaDao.findByName(entity.getPersonaName());
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
        dto.setAssignedAgentId(meta.getAssignedAgentId());
        if (!StringUtil.isBlank(meta.getAssignedAgentId())) {
            AgentRuntimeEntity agent = agentRuntimeDao.findByAgentId(meta.getAssignedAgentId());
            dto.setAssignedAgentName(agent == null ? null : agent.getName());
        }
        dto.setUpdatedAt(meta.getUpdateTime());
        return dto;
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

    private void validateAgentStatus(String status) {
        require(List.of(AgentConstants.STATUS_ONLINE, AgentConstants.STATUS_BUSY, AgentConstants.STATUS_OFFLINE,
                AgentConstants.STATUS_ERROR).contains(status), "Invalid agent status");
    }

    private void validateTaskStatus(String status) {
        require(List.of(AgentConstants.TASK_STATUS_OPEN, AgentConstants.TASK_STATUS_ASSIGNED,
                AgentConstants.TASK_STATUS_RUNNING, AgentConstants.TASK_STATUS_COMPLETED,
                AgentConstants.TASK_STATUS_FAILED).contains(status), "Invalid task status");
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
