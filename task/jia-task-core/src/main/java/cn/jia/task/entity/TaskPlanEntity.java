package cn.jia.task.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
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
@TableName("task_plan")
@Schema(name = "TaskPlan对象", description="")
public class TaskPlanEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "JIA账号")
    private String jiacn;

    @Schema(description = "任务类型 1常规提醒 2目标 3还款计划 4固定收入")
    private Integer type;

    @Schema(description = "周期类型 0长期 1每年 2每月 3每周 5每日 11每小时 12每分钟 13每秒 6指定日期")
    private Integer period;

    @Schema(description = "周期表达式")
    private String crond;

    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "任务描述")
    private String description;

    @Schema(description = "是否农历日期 1是 0否")
    private Integer lunar;

    @Schema(description = "开始时间")
    private Long startTime;

    @Schema(description = "结束时间")
    private Long endTime;

    @Schema(description = "数量/金额")
    private BigDecimal amount;

    @Schema(description = "是否需要提醒 1是 0否")
    private Integer remind;

    @Schema(description = "提醒手机号码")
    private String remindPhone;

    @Schema(description = "提醒信息")
    private String remindMsg;

    @Schema(description = "状态 1有效 0无效")
    private Integer status;

}
