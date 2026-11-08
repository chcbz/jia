package cn.jia.economy.mapper;

import cn.jia.economy.entity.EconomyUsageAttemptEntity;
import cn.jia.economy.entity.EconomyUsageRevisionEntity;
import cn.jia.economy.entity.EconomyTaskCostEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Cost-only persistence boundary. Scope is explicit because these are curated native SQL statements. */
public interface EconomyUsageCostMapper {
    String TASK = "tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn} AND task_id=#{taskId}";
    String ATTEMPT = "tenant_id=#{tenantId} AND client_id=#{clientId} AND attempt_id=#{attemptId}";

    @Insert("""
            INSERT INTO economy_task_cost(tenant_id,client_id,owner_jiacn,task_id,attempts,pending_usage,pending_price,cost_yuan,updated_at)
            VALUES(#{tenantId},#{clientId},#{ownerJiacn},#{taskId},0,0,0,0,#{updatedAt})
            ON DUPLICATE KEY UPDATE task_id=task_id
            """)
    int insertSummaryIfAbsent(EconomyTaskCostEntity summary);

    @Select("SELECT * FROM economy_task_cost WHERE " + TASK + " FOR UPDATE")
    EconomyTaskCostEntity lockSummary(EconomyTaskCostEntity key);

    @Select("SELECT * FROM economy_task_cost WHERE " + TASK)
    EconomyTaskCostEntity findSummary(@Param("tenantId") String tenant, @Param("clientId") String client,
            @Param("ownerJiacn") String owner, @Param("taskId") String task);

    @Update("UPDATE economy_task_cost SET attempts=#{attempts},pending_usage=#{pendingUsage},pending_price=#{pendingPrice},"
            + "cost_yuan=#{costYuan},updated_at=#{updatedAt} WHERE " + TASK)
    int updateSummary(EconomyTaskCostEntity summary);

    @Insert("""
            INSERT INTO economy_usage_attempt(tenant_id,client_id,attempt_id,owner_jiacn,task_id,source,source_attempt_id,
                revision,status,cost_yuan,created_at,updated_at)
            VALUES(#{tenantId},#{clientId},#{attemptId},#{ownerJiacn},#{taskId},#{source},#{sourceAttemptId},0,NULL,NULL,#{createdAt},#{updatedAt})
            ON DUPLICATE KEY UPDATE attempt_id=attempt_id
            """)
    int insertAttemptIfAbsent(EconomyUsageAttemptEntity attempt);

    @Select("SELECT * FROM economy_usage_attempt WHERE " + ATTEMPT + " FOR UPDATE")
    EconomyUsageAttemptEntity lockAttempt(EconomyUsageAttemptEntity key);

    @Update("UPDATE economy_usage_attempt SET revision=#{revision},status=#{status},cost_yuan=#{costYuan},"
            + "updated_at=#{updatedAt} WHERE " + ATTEMPT)
    int updateAttempt(EconomyUsageAttemptEntity attempt);

    @Select("SELECT * FROM economy_usage_revision WHERE " + ATTEMPT + " AND receipt_key=#{receiptKey}")
    EconomyUsageRevisionEntity findReceipt(EconomyUsageRevisionEntity key);

    @Select("SELECT * FROM economy_usage_attempt WHERE " + ATTEMPT)
    EconomyUsageAttemptEntity findAttempt(EconomyUsageAttemptEntity key);

    /** Append-only revisions: no update/delete mapper is provided. */
    @Insert("""
            INSERT INTO economy_usage_revision(tenant_id,client_id,attempt_id,revision,receipt_key,receipt_id,request_sha256,
                raw_usage_json,normalized_usage_json,price_snapshot_json,status,cost_yuan,observed_at,recorded_at)
            VALUES(#{tenantId},#{clientId},#{attemptId},#{revision},#{receiptKey},#{receiptId},#{requestSha256},
                #{rawUsageJson},#{normalizedUsageJson},#{priceSnapshotJson},#{status},#{costYuan},#{observedAt},#{recordedAt})
            """)
    int insertRevision(EconomyUsageRevisionEntity revision);
}
