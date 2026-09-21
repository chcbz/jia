package cn.jia.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/** Immutable owner-scoped case-to-execution lineage. */
@Data
@Accessors(chain = true)
@TableName("hall_case_execution")
public class HallCaseExecutionEntity implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String caseId;
    private String executionId;
    private Long revisionNo;
    private String parentExecutionId;
    private String sourceOutputRefJson;
    private Long createdAt;
}
