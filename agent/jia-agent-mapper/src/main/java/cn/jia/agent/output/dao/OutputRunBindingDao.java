package cn.jia.agent.output.dao;

import cn.jia.agent.output.entity.OutputRunBindingEntity;
public interface OutputRunBindingDao {
    int insert(OutputRunBindingEntity entity);

    OutputRunBindingEntity findExactByRun(
            String tenantId, String clientId, String runId, boolean forUpdate);

    OutputRunBindingEntity findExactByOrigin(
            String tenantId, String clientId, String sourceType, String sourceId,
            String producerAgentId, String originType, String originId);

    /** Fenced terminal transition performed by the formal-delivery transaction. */
    default int markResultSubmitted(OutputRunBindingEntity expected, long updatedAt) {
        throw new UnsupportedOperationException("Formal delivery run transition is unavailable");
    }
}
