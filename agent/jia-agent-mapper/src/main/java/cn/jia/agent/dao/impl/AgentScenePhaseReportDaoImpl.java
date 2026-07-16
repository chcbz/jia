package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentScenePhaseReportDao;
import cn.jia.agent.entity.AgentScenePhaseReportEntity;
import cn.jia.agent.mapper.AgentScenePhaseReportMapper;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentScenePhaseReportEntity findByReportIdForUpdate(
            String tenantId, String clientId, String sceneId, String reportId) {
        requireScope(tenantId, clientId, sceneId);
        if (StringUtil.isBlank(reportId)) {
            throw new IllegalArgumentException("reportId is required");
        }
        return baseMapper.selectScopedForUpdate(tenantId, clientId, sceneId, reportId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryReserve(String tenantId, String clientId, String sceneId,
            AgentScenePhaseReportEntity entity) {
        requireScope(tenantId, clientId, sceneId);
        requireReservation(entity);
        entity.setTenantId(tenantId);
        entity.setClientId(clientId);
        entity.setSceneId(sceneId);
        requireMaxLength("tenantId", tenantId, 50);
        requireMaxLength("clientId", clientId, 50);
        requireMaxLength("sceneId", sceneId, 100);
        entity.init4Creation();
        int inserted = baseMapper.reserveIgnore(entity);
        if (inserted == 1) {
            return true;
        }
        if (inserted == 0) {
            return false;
        }
        throw new IllegalStateException("Phase report reservation affected an unexpected row count: " + inserted);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int finalizePendingResult(String tenantId, String clientId, String sceneId,
            String reportId, String pendingResult, String finalResult, long processedAt) {
        requireScope(tenantId, clientId, sceneId);
        if (StringUtil.isBlank(reportId) || StringUtil.isBlank(pendingResult)
                || StringUtil.isBlank(finalResult) || processedAt < 0) {
            throw new IllegalArgumentException(
                    "reportId, pendingResult, finalResult and nonnegative processedAt are required");
        }
        AgentScenePhaseReportEntity update = new AgentScenePhaseReportEntity();
        update.setResult(finalResult);
        update.setProcessedAt(processedAt);
        update.setUpdateTime(processedAt);
        return baseMapper.update(update, scope(tenantId, clientId, sceneId)
                .eq(AgentScenePhaseReportEntity::getReportId, reportId)
                .eq(AgentScenePhaseReportEntity::getResult, pendingResult));
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

    private void requireReservation(AgentScenePhaseReportEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("phase report is required");
        }
        requireText("reportId", entity.getReportId(), 100);
        requireText("agentId", entity.getAgentId(), 100);
        requireText("phase", entity.getPhase(), 20);
        requireText("regionId", entity.getRegionId(), 100);
        requireText("result", entity.getResult(), 30);
        if (entity.getStateVersion() == null || entity.getStateVersion() <= 0) {
            throw new IllegalArgumentException("stateVersion must be positive");
        }
        if (entity.getOccurredAt() == null || entity.getOccurredAt() < 0) {
            throw new IllegalArgumentException("occurredAt must be nonnegative");
        }
        if (entity.getProcessedAt() == null || entity.getProcessedAt() < 0) {
            throw new IllegalArgumentException("processedAt must be nonnegative");
        }
    }

    private void requireText(String name, String value, int maxLength) {
        if (StringUtil.isBlank(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        requireMaxLength(name, value, maxLength);
    }

    private void requireMaxLength(String name, String value, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds maximum length " + maxLength);
        }
    }
}
