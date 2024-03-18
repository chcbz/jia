package cn.jia.mat.entity;

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
 * @since 2021-10-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("mat_media")
@Schema(name = "MatMedia对象")
public class MatMediaEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @Schema(description = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "类型 1图片 2语音 3视频 4缩略图 5富文本")
    private Integer type;

    @Schema(description = "路径")
    private String url;

    @Schema(description = "资源ID")
    private String resourceId;

    @Schema(description = "实体ID")
    private Long entityId;

}
