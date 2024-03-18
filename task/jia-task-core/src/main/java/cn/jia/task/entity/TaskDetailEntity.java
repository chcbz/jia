package cn.jia.task.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("v_task_item")
@Schema(name = "VTaskItem对象", description="")
public class TaskDetailEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    private Long id;

    private Long planId;

    private String jiacn;

    private Integer type;

    private Integer period;

    private String crond;

    private String name;

    private String description;

    private BigDecimal amount;

    private Integer remind;

    private String remindPhone;

    private String remindMsg;

    private Integer status;

}
