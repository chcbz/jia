package cn.jia.mat.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

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
@Schema(name = "MatMedia对象", description="")
public class MatMediaEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @Schema(description = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "应用标识码")
    private String clientId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "类型 1图片 2语音 3视频 4缩略图 5富文本")
    private Integer type;

    @Schema(description = "路径")
    private String url;

    @Schema(description = "更新时间")
    private Long time;


}
