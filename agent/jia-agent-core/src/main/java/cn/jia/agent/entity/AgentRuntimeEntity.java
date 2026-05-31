package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_runtime")
@Schema(name = "AgentRuntime对象", description = "聚义厅Agent运行时注册信息")
public class AgentRuntimeEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String agentId;
    private String name;
    private String avatar;
    private String personaName;
    private String abilities;
    private String endpoint;
    private String tokenHash;
    private String status;
    private String currentTaskId;
    private String currentTaskTitle;
    private Long lastSeenAt;
    private String errorMessage;
}
