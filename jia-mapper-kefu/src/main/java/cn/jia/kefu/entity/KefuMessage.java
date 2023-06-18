package cn.jia.kefu.entity;

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
 * 留言信息
 * </p>
 *
 * @author chc
 * @since 2021-01-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("kefu_message")
@Schema(name = "KefuMessage对象", description="")
public class KefuMessage extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "资源ID")
    private String resourceId;

    @Schema(description = "应用标识符")
    private String clientId;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "姓名")
    private String name;

    @Schema(description = "电话号码")
    private String phone;

    @Schema(description = "邮箱地址")
    private String email;

    private String title;

    private String content;

    private String attachment;

    @Schema(description = "回复内容")
    private String reply;

    @Schema(description = "状态 0待回复 1已回复")
    private Integer status;


}
