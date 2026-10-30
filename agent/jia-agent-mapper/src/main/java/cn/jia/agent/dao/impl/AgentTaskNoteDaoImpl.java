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
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        var query = new LambdaQueryWrapper<AgentTaskNoteEntity>()
                .eq(AgentTaskNoteEntity::getTenantId, tenantId)
                .eq(AgentTaskNoteEntity::getClientId, clientId)
                .eq(AgentTaskNoteEntity::getOwnerJiacn, ownerJiacn)
                .eq(AgentTaskNoteEntity::getTaskId, taskId)
                .orderByAsc(AgentTaskNoteEntity::getCreatedAt)
                .orderByAsc(AgentTaskNoteEntity::getId);
        TaskCollaborationDaoSupport.exact(query, "tenant_id", tenantId);
        TaskCollaborationDaoSupport.exact(query, "client_id", clientId);
        TaskCollaborationDaoSupport.exact(query, "owner_jiacn", ownerJiacn);
        TaskCollaborationDaoSupport.exact(query, "task_id", taskId);
        return baseMapper.selectList(query);
    }
}
