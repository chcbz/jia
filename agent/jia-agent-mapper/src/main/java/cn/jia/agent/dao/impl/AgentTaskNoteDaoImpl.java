package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.entity.AgentTaskNoteEntity;
import cn.jia.agent.mapper.AgentTaskNoteMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentTaskNoteDaoImpl extends BaseDaoImpl<AgentTaskNoteMapper, AgentTaskNoteEntity> implements AgentTaskNoteDao {
    @Override
    public List<AgentTaskNoteEntity> findByTaskId(String taskId) {
        return baseMapper.selectList(new LambdaQueryWrapper<AgentTaskNoteEntity>()
                .eq(AgentTaskNoteEntity::getTaskId, taskId)
                .orderByAsc(AgentTaskNoteEntity::getCreatedAt)
                .orderByAsc(AgentTaskNoteEntity::getId));
    }

    @Override
    public List<AgentTaskNoteEntity> findByTaskIdInOwnerScope(
            String tenantId, String clientId, String ownerJiacn, String taskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        if (!"0".equals(tenantId) || ownerJiacn == null || ownerJiacn.isBlank()
                || ownerJiacn.equals("0")) {
            throw new IllegalArgumentException("strict task owner scope is invalid");
        }
        return baseMapper.selectList(new LambdaQueryWrapper<AgentTaskNoteEntity>()
                .eq(AgentTaskNoteEntity::getTenantId, tenantId)
                .eq(AgentTaskNoteEntity::getClientId, clientId)
                .eq(AgentTaskNoteEntity::getOwnerJiacn, ownerJiacn)
                .eq(AgentTaskNoteEntity::getTaskId, taskId)
                .orderByAsc(AgentTaskNoteEntity::getCreatedAt)
                .orderByAsc(AgentTaskNoteEntity::getId));
    }
}
