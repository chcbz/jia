package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.entity.DialogueTemplateEntity;
import cn.jia.agent.mapper.DialogueTemplateMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class DialogueTemplateDaoImpl extends BaseDaoImpl<DialogueTemplateMapper, DialogueTemplateEntity> implements DialogueTemplateDao {
    @Override
    public List<DialogueTemplateEntity> findByPersonaAndType(String personaName, String dialogueType) {
        return baseMapper.selectList(new LambdaQueryWrapper<DialogueTemplateEntity>()
                .eq(DialogueTemplateEntity::getPersonaName, personaName)
                .eq(DialogueTemplateEntity::getDialogueType, dialogueType)
                .eq(DialogueTemplateEntity::getActive, true)
                .orderByDesc(DialogueTemplateEntity::getPriority));
    }
}
