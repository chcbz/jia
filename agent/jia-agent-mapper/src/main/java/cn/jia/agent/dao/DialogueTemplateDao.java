package cn.jia.agent.dao;

import cn.jia.agent.entity.DialogueTemplateEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface DialogueTemplateDao extends IBaseDao<DialogueTemplateEntity> {
    List<DialogueTemplateEntity> findByPersonaAndType(String personaName, String dialogueType);
}
