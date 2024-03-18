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
@TableName("mat_phrase")
@Schema(name = "MatPhrase对象")
public class MatPhraseEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @Schema(description = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "标签")
    private String tag;

    @Schema(description = "状态 1有效 0无效")
    private Integer status;

    @Schema(description = "阅读量")
    private Integer pv;

    @Schema(description = "点赞量")
    private Integer up;

    @Schema(description = "点踩量")
    private Integer down;

    @Schema(description = "提交人JIA账号")
    private String jiacn;


}
