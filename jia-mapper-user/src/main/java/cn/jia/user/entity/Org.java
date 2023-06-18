package cn.jia.user.entity;

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
 * @since 2021-11-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("user_org")
@Schema(name = "Org对象", description="")
public class Org extends BaseEntity {

    private static final long serialVersionUID=1L;

    @Schema(description = "组织ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String clientId;

    @Schema(description = "组织名称")
    private String name;

    @Schema(description = "夫组织ID")
    private Integer pId;

    @Schema(description = "机构类型 1公司 2子公司 3总监 4经理 5职员 6客户")
    private Integer type;

    @Schema(description = "机构编码")
    private String code;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "负责人")
    private String director;

    private String logo;

    private String logoIcon;

    @Schema(description = "状态(1:正常,0:停用)")
    private Integer status;


}
