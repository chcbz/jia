package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.entity.AgentTaskArtifactDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.mapper.AgentTaskArtifactMapper;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentTaskArtifactDaoImpl implements AgentTaskArtifactDao {
    private final AgentTaskArtifactMapper baseMapper;

    @Inject
    public AgentTaskArtifactDaoImpl(AgentTaskArtifactMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    @Override
    public int insert(String tenantId, String clientId, AgentTaskArtifactDTO artifact) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireArtifact(artifact);
        AgentTaskArtifactEntity entity = toEntity(artifact);
        TaskCollaborationDaoSupport.applyScope(entity, tenantId, clientId);
        entity.setVisibility(StringUtil.isBlank(artifact.getVisibility())
                ? "task_members" : artifact.getVisibility());
        entity.setCreatedAt(artifact.getCreatedAt() == null ? DateUtil.nowTime() : artifact.getCreatedAt());
        entity.init4Creation();
        return baseMapper.insert(entity);
    }

    @Override
    public AgentTaskArtifactEntity findVersion(
            String tenantId, String clientId, String taskId, String artifactId, int artifactVersion) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(artifactId, "artifactId");
        requireVersion(artifactVersion);
        return baseMapper.selectOne(scope(tenantId, clientId)
                .eq(AgentTaskArtifactEntity::getTaskId, taskId)
                .eq(AgentTaskArtifactEntity::getArtifactId, artifactId)
                .eq(AgentTaskArtifactEntity::getArtifactVersion, artifactVersion)
                .last("limit 1"));
    }

    @Override
    public AgentTaskArtifactEntity findLatestVersion(
            String tenantId, String clientId, String taskId, String artifactId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(artifactId, "artifactId");
        return baseMapper.selectOne(scope(tenantId, clientId)
                .eq(AgentTaskArtifactEntity::getTaskId, taskId)
                .eq(AgentTaskArtifactEntity::getArtifactId, artifactId)
                .orderByDesc(AgentTaskArtifactEntity::getArtifactVersion)
                .orderByDesc(AgentTaskArtifactEntity::getId)
                .last("limit 1"));
    }

    @Override
    public AgentTaskArtifactEntity findLatestVersionForUpdate(
            String tenantId, String clientId, String taskId, String artifactId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(artifactId, "artifactId");
        return baseMapper.selectLatestVersionForUpdate(tenantId, clientId, taskId, artifactId);
    }

    @Override
    public List<AgentTaskArtifactEntity> listVersions(
            String tenantId, String clientId, String taskId, String artifactId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(artifactId, "artifactId");
        return baseMapper.selectList(scope(tenantId, clientId)
                .eq(AgentTaskArtifactEntity::getTaskId, taskId)
                .eq(AgentTaskArtifactEntity::getArtifactId, artifactId)
                .orderByDesc(AgentTaskArtifactEntity::getArtifactVersion)
                .orderByDesc(AgentTaskArtifactEntity::getId));
    }

    @Override
    public List<AgentTaskArtifactEntity> listByTask(
            String tenantId, String clientId, String taskId, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        return ordered(scope(tenantId, clientId)
                .eq(AgentTaskArtifactEntity::getTaskId, taskId), limit);
    }

    @Override
    public List<AgentTaskArtifactEntity> listVisibleByTask(
            String tenantId, String clientId, String taskId, String workItemId,
            String actorAgentId, boolean reviewerAccess, boolean coordinatorAccess, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(actorAgentId, "actorAgentId");
        LambdaQueryWrapper<AgentTaskArtifactEntity> wrapper = scope(tenantId, clientId)
                .eq(AgentTaskArtifactEntity::getTaskId, taskId)
                .in(AgentTaskArtifactEntity::getVisibility,
                        "task_members", "reviewer", "private");
        if (!StringUtil.isBlank(workItemId)) {
            TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
            wrapper.eq(AgentTaskArtifactEntity::getWorkItemId, workItemId);
        }
        if (!coordinatorAccess) {
            wrapper.and(readable -> {
                readable.eq(AgentTaskArtifactEntity::getVisibility, "task_members")
                        .or().eq(AgentTaskArtifactEntity::getProducerAgentId, actorAgentId);
                if (reviewerAccess) {
                    readable.or().eq(AgentTaskArtifactEntity::getVisibility, "reviewer");
                }
            });
        }
        return ordered(wrapper, limit);
    }

    @Override
    public List<AgentTaskArtifactEntity> listByWorkItem(
            String tenantId, String clientId, String taskId, String workItemId, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
        return ordered(scope(tenantId, clientId)
                .eq(AgentTaskArtifactEntity::getTaskId, taskId)
                .eq(AgentTaskArtifactEntity::getWorkItemId, workItemId), limit);
    }

    private List<AgentTaskArtifactEntity> ordered(
            LambdaQueryWrapper<AgentTaskArtifactEntity> wrapper, int limit) {
        return baseMapper.selectList(wrapper
                .orderByDesc(AgentTaskArtifactEntity::getCreatedAt)
                .orderByAsc(AgentTaskArtifactEntity::getArtifactId)
                .orderByDesc(AgentTaskArtifactEntity::getArtifactVersion)
                .orderByDesc(AgentTaskArtifactEntity::getId)
                .last("limit " + TaskCollaborationDaoSupport.boundedLimit(limit)));
    }

    private LambdaQueryWrapper<AgentTaskArtifactEntity> scope(String tenantId, String clientId) {
        return new LambdaQueryWrapper<AgentTaskArtifactEntity>()
                .eq(AgentTaskArtifactEntity::getTenantId, tenantId)
                .eq(AgentTaskArtifactEntity::getClientId, clientId);
    }

    private void requireArtifact(AgentTaskArtifactDTO artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact is required");
        }
        TaskCollaborationDaoSupport.requireId(artifact.getArtifactId(), "artifactId");
        TaskCollaborationDaoSupport.requireId(artifact.getTaskId(), "taskId");
        TaskCollaborationDaoSupport.requireId(artifact.getProducerAgentId(), "producerAgentId");
        TaskCollaborationDaoSupport.requireId(artifact.getArtifactType(), "artifactType");
        TaskCollaborationDaoSupport.requireId(artifact.getTitle(), "title");
        if (artifact.getArtifactVersion() == null) {
            throw new IllegalArgumentException("artifactVersion is required");
        }
        requireVersion(artifact.getArtifactVersion());
    }

    private void requireVersion(int artifactVersion) {
        if (artifactVersion < 1) {
            throw new IllegalArgumentException("artifactVersion must be positive");
        }
    }

    private AgentTaskArtifactEntity toEntity(AgentTaskArtifactDTO artifact) {
        return new AgentTaskArtifactEntity()
                .setArtifactId(artifact.getArtifactId())
                .setTaskId(artifact.getTaskId())
                .setWorkItemId(artifact.getWorkItemId())
                .setProducerAgentId(artifact.getProducerAgentId())
                .setArtifactType(artifact.getArtifactType())
                .setTitle(artifact.getTitle())
                .setContent(artifact.getContent())
                .setStorageUri(artifact.getStorageUri())
                .setContentHash(artifact.getContentHash())
                .setArtifactVersion(artifact.getArtifactVersion())
                .setMetadataJson(artifact.getMetadataJson());
    }
}
