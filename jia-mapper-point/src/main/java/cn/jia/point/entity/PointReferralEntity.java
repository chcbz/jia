package cn.jia.point.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
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
 * @since 2021-02-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Schema(name = "PointReferral对象", description="")
public class PointReferralEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @Schema(description = "推荐ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "推荐人Jia账号")
    private String referrer;

    @Schema(description = "被推荐人Jia账号")
    private String referral;

    @Schema(description = "时间")
    private Long time;


}
