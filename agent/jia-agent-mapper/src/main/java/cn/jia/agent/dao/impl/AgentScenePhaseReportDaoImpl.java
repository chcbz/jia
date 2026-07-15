package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentScenePhaseReportDao;
import cn.jia.agent.entity.AgentScenePhaseReportEntity;
import cn.jia.agent.mapper.AgentScenePhaseReportMapper;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class AgentScenePhaseReportDaoImpl implements AgentScenePhaseReportDao {
    private final AgentScenePhaseReportMapper baseMapper;

    @Inject
    public AgentScenePhaseReportDaoImpl(AgentScenePhaseReportMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    @Override
    public AgentScenePhaseReportEntity findByReportId(
            String tenantId, String clientId, String sceneId, String reportId) {
        requireScope(tenantId, clientId, sceneId);
        if (StringUtil.isBlank(reportId)) {
            throw new IllegalArgumentException("reportId is required");
        }
        return baseMapper.selectOne(scope(tenantId, clientId, sceneId)
                .eq(AgentScenePhaseReportEntity::getReportId, reportId)
                .last("limit 1"));
    }

    @Override
    public int insert(String tenantId, String clientId, String sceneId,
            AgentScenePhaseReportEntity entity) {
        requireScope(tenantId, clientId, sceneId);
        if (entity == null || StringUtil.isBlank(entity.getReportId())) {
            throw new IllegalArgumentException("phase report and reportId are required");
        }
        entity.setTenantId(tenantId);
        entity.setClientId(clientId);
        entity.setSceneId(sceneId);
        entity.init4Creation();
        return baseMapper.insert(entity);
    }

    private LambdaQueryWrapper<AgentScenePhaseReportEntity> scope(
            String tenantId, String clientId, String sceneId) {
        return new LambdaQueryWrapper<AgentScenePhaseReportEntity>()
                .eq(AgentScenePhaseReportEntity::getTenantId, tenantId)
                .eq(AgentScenePhaseReportEntity::getClientId, clientId)
                .eq(AgentScenePhaseReportEntity::getSceneId, sceneId);
    }

    private void requireScope(String tenantId, String clientId, String sceneId) {
        if (StringUtil.isBlank(tenantId) || StringUtil.isBlank(clientId) || StringUtil.isBlank(sceneId)) {
            throw new IllegalArgumentException("tenantId, clientId and sceneId are required");
        }
    }
}
