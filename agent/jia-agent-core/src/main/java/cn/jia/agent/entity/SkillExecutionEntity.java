package cn.jia.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/** Skill-specific attachment to PWE, not a second admission or completion authority.
 * Snapshot identity is immutable; mutable fields are written only through scope-exact CAS.
 * Deliberately does not inherit BaseEntity's unrelated create_time/update_time columns. */
@Data
@Accessors(chain = true)
@TableName("agent_skill_execution")
public class SkillExecutionEntity implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String executionId;
    private String skillKey;
    private String skillVersion;
    private String packageSha256;
    private String manifestSha256;
    @ToString.Exclude private String snapshotJson;
    private String snapshotSha256;
    private String phase;
    @ToString.Exclude private String effectsJson;
    @ToString.Exclude private String resultManifestJson;
    private String lastErrorCode;
    private Long version;
    private Long createdAt;
    private Long updatedAt;
}
