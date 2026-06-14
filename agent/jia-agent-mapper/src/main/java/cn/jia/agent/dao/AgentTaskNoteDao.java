package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskNoteEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentTaskNoteDao extends IBaseDao<AgentTaskNoteEntity> {
    List<AgentTaskNoteEntity> findByTaskId(String taskId);
}
