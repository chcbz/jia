package cn.jia.kefu.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-06-01
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("kefu_msg_log")
@Schema(name = "KefuMsgLog对象", description="")
public class KefuMsgLogEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "地址")
    private String url;

    @Schema(description = "类型")
    private String type;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "状态 1未读 2已读 0已删除")
    private Integer status;


}
