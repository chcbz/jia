package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskArtifactEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentTaskOutputMapper {
    @Select("""
            SELECT * FROM agent_task_artifact
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND task_id=#{taskId}
              AND artifact_id=#{artifactId} AND artifact_version=#{version}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND CAST(artifact_id AS BINARY)=CAST(#{artifactId} AS BINARY)
            LIMIT 1
            """)
    AgentTaskArtifactEntity findVersion(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId,
            @Param("artifactId") String artifactId, @Param("version") long version);

    @Select("""
            <script>
            SELECT a.* FROM agent_task_artifact a
            WHERE a.tenant_id=#{tenantId} AND a.client_id=#{clientId} AND a.task_id=#{taskId}
              AND CAST(a.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(a.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(a.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(a.client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(a.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(a.task_id)=OCTET_LENGTH(#{taskId})
              AND a.owner_shared_at IS NOT NULL AND a.retain_until&gt;#{now}
              AND a.created_at&lt;=#{snapshotAt}
            <if test="artifactId != null">
              AND a.artifact_id=#{artifactId}
              AND CAST(a.artifact_id AS BINARY)=CAST(#{artifactId} AS BINARY)
            </if>
            <if test="latestOnly">
              AND NOT EXISTS (SELECT 1 FROM agent_task_artifact n
                WHERE n.tenant_id=a.tenant_id AND n.client_id=a.client_id
                  AND n.task_id=a.task_id AND n.artifact_id=a.artifact_id
                  AND CAST(n.tenant_id AS BINARY)=CAST(a.tenant_id AS BINARY)
                  AND OCTET_LENGTH(n.tenant_id)=OCTET_LENGTH(a.tenant_id)
                  AND CAST(n.client_id AS BINARY)=CAST(a.client_id AS BINARY)
                  AND OCTET_LENGTH(n.client_id)=OCTET_LENGTH(a.client_id)
                  AND CAST(n.task_id AS BINARY)=CAST(a.task_id AS BINARY)
                  AND OCTET_LENGTH(n.task_id)=OCTET_LENGTH(a.task_id)
                  AND CAST(n.artifact_id AS BINARY)=CAST(a.artifact_id AS BINARY)
                  AND OCTET_LENGTH(n.artifact_id)=OCTET_LENGTH(a.artifact_id)
                  AND n.owner_shared_at IS NOT NULL AND n.retain_until&gt;#{now}
                  AND n.artifact_version&gt;a.artifact_version)
            </if>
            <if test="afterCreatedAt != null">
              AND (a.created_at&lt;#{afterCreatedAt} OR (a.created_at=#{afterCreatedAt} AND
                   (CAST(a.artifact_id AS BINARY)&lt;CAST(#{afterArtifactId} AS BINARY)
                    OR (CAST(a.artifact_id AS BINARY)=CAST(#{afterArtifactId} AS BINARY)
                        AND a.artifact_version&lt;#{afterVersion}))))
            </if>
            ORDER BY a.created_at DESC, CAST(a.artifact_id AS BINARY) DESC,
                     a.artifact_version DESC LIMIT #{limit}
            </script>
            """)
    List<AgentTaskArtifactEntity> listOwnerVisible(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("taskId") String taskId,
            @Param("artifactId") String artifactId, @Param("latestOnly") boolean latestOnly,
            @Param("snapshotAt") long snapshotAt, @Param("now") long now,
            @Param("afterCreatedAt") Long afterCreatedAt,
            @Param("afterArtifactId") String afterArtifactId,
            @Param("afterVersion") Long afterVersion, @Param("limit") int limit);
}
