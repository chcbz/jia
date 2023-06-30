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
@Schema(name = "PointSign对象", description="")
public class PointSignEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @Schema(description = "签到ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "时间")
    private Long time;

    @Schema(description = "地点")
    private String address;

    @Schema(description = "纬度")
    private String latitude;

    @Schema(description = "经度")
    private String longitude;

    @Schema(description = "当此得分")
    private Integer point;


}
