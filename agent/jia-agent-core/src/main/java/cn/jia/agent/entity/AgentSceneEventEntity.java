package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_scene_event")
public class AgentSceneEventEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String sceneId;
    private Long sceneVersion;
    private String eventType;
    private String eventJson;
    private Long occurredAt;
}
