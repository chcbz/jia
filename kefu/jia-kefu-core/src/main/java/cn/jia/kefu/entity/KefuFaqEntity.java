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
 * 常见问题
 * </p>
 *
 * @author chc
 * @since 2021-01-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("kefu_faq")
@Schema(name = "KefuFaq对象", description="")
public class KefuFaqEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "类型")
    private String type;

    @Schema(description = "应用标识符")
    private String clientId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "点击量")
    private Integer click;

    @Schema(description = "点赞数量")
    private Integer useful;

    @Schema(description = "点踩数量")
    private Integer useless;

    @Schema(description = "状态 0无效 1有效")
    private Integer status;


}
