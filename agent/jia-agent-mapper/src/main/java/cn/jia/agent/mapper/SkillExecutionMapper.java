package cn.jia.agent.mapper;

import cn.jia.agent.entity.SkillExecutionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** No unscoped BaseMapper writes. Caller must hold the existing runtime/task/PWE fences
 * and commit main and attachment transitions in one transaction. */
public interface SkillExecutionMapper {
    String EXACT_SCOPE = """
            tenant_id=#{tenant} AND client_id=#{client} AND owner_jiacn=#{owner}
            AND execution_id=#{execution}
            AND CAST(tenant_id AS BINARY)=CAST(#{tenant} AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenant})
            AND CAST(client_id AS BINARY)=CAST(#{client} AS BINARY)
            AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{client})
            AND CAST(owner_jiacn AS BINARY)=CAST(#{owner} AS BINARY)
            AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{owner})
            AND CAST(execution_id AS BINARY)=CAST(#{execution} AS BINARY)
            AND OCTET_LENGTH(execution_id)=OCTET_LENGTH(#{execution})
            """;

    @Select("SELECT * FROM agent_skill_execution WHERE " + EXACT_SCOPE)
    SkillExecutionEntity find(@Param("tenant") String tenant, @Param("client") String client,
            @Param("owner") String owner, @Param("execution") String execution);

    @Select("SELECT * FROM agent_skill_execution WHERE " + EXACT_SCOPE + " FOR UPDATE")
    SkillExecutionEntity lock(@Param("tenant") String tenant, @Param("client") String client,
            @Param("owner") String owner, @Param("execution") String execution);

    @Insert("""
            INSERT INTO agent_skill_execution
                (tenant_id,client_id,owner_jiacn,execution_id,skill_key,skill_version,
                 package_sha256,manifest_sha256,snapshot_json,snapshot_sha256,task_skill_binding_json,
                 phase,effects_json,result_manifest_json,last_error_code,version,created_at,updated_at)
            VALUES (#{row.tenantId},#{row.clientId},#{row.ownerJiacn},#{row.executionId},
                    #{row.skillKey},#{row.skillVersion},#{row.packageSha256},#{row.manifestSha256},
                    #{row.snapshotJson},#{row.snapshotSha256},#{row.taskSkillBindingJson},'READY','{}',NULL,NULL,0,
                    #{row.createdAt},#{row.createdAt})
            """)
    int insertReady(@Param("row") SkillExecutionEntity row);

    /** One-time projection attachment only; the original admitted snapshot/hash is never rewritten. */
    @Update("UPDATE agent_skill_execution SET task_skill_binding_json=#{binding},version=version+1,updated_at=#{now} WHERE "+EXACT_SCOPE+
            " AND task_skill_binding_json IS NULL AND snapshot_sha256=#{snapshot} AND version=#{version}")
    int bindTaskSkill(@Param("tenant") String tenant,@Param("client") String client,@Param("owner") String owner,@Param("execution") String execution,
            @Param("snapshot") String snapshot,@Param("version") long version,@Param("binding") String binding,@Param("now") long now);

    /** Full mutable projection, version-fenced. Does not change the frozen snapshot or scope.
     * Same-phase CAS is needed for effect registration/result receipts, but never for terminals.
     * Services must still validate effect/result digests; this is not authorization. */
    @Update("""
            UPDATE agent_skill_execution
               SET phase=#{row.phase},effects_json=#{row.effectsJson},
                   result_manifest_json=#{row.resultManifestJson},last_error_code=#{row.lastErrorCode},
                   version=version+1,updated_at=#{row.updatedAt}
             WHERE
            """ + EXACT_SCOPE + """
               AND version=#{expectedVersion} AND phase=#{expectedPhase}
               AND CAST(phase AS BINARY)=CAST(#{expectedPhase} AS BINARY)
               AND OCTET_LENGTH(phase)=OCTET_LENGTH(#{expectedPhase})
               AND (
                 (phase='READY' AND #{row.phase} IN ('READY','RUNNING','CANCELLED'))
                 OR (phase='RUNNING' AND #{row.phase} IN
                     ('RUNNING','WAITING_PROVIDER','RESULT_UNKNOWN','RESULT_READY','FAILED','CANCELLED'))
                 OR (phase='WAITING_PROVIDER' AND #{row.phase} IN
                     ('WAITING_PROVIDER','RESULT_UNKNOWN','RESULT_READY','FAILED','CANCELLED'))
                 OR (phase='RESULT_UNKNOWN' AND #{row.phase} IN
                     ('RESULT_UNKNOWN','WAITING_PROVIDER','RESULT_READY','FAILED'))
                 OR (phase='RESULT_READY' AND #{row.phase} IN ('RESULT_READY','ARCHIVING'))
                 OR (phase='ARCHIVING' AND #{row.phase} IN ('ARCHIVING','COMPLETE'))
               )
            """)
    int compareAndSet(@Param("tenant") String tenant, @Param("client") String client,
            @Param("owner") String owner, @Param("execution") String execution,
            @Param("expectedVersion") long expectedVersion, @Param("expectedPhase") String expectedPhase,
            @Param("row") SkillExecutionEntity row);
}
