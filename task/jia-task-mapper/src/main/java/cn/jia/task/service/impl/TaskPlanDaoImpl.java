package cn.jia.task.service.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.task.dao.TaskPlanDao;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.mapper.TaskPlanMapper;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
@Named
public class TaskPlanDaoImpl extends BaseDaoImpl<TaskPlanMapper, TaskPlanEntity> implements TaskPlanDao {

    @Override
    public TaskPlanEntity findInOwnerScope(
            String tenantId, String clientId, String ownerJiacn, Long id) {
        requireScopeId(tenantId, "tenantId", 50);
        requireScopeId(clientId, "clientId", 50);
        requireScopeId(ownerJiacn, "ownerJiacn", 50);
        if ("0".equals(ownerJiacn)) throw new IllegalArgumentException("ownerJiacn");
        if (id == null) throw new IllegalArgumentException("id");
        return baseMapper.findInOwnerScope(tenantId, clientId, ownerJiacn, id);
    }

    private static void requireScopeId(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name);
        }
    }
}
