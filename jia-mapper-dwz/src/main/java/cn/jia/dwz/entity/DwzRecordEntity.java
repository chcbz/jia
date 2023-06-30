package cn.jia.dwz.entity;

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
 * @since 2021-10-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("dwz_record")
@Schema(name = "DwzRecord对象", description="")
public class DwzRecordEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "原地址")
    private String orgi;

    @Schema(description = "目标地址")
    private String uri;

    @Schema(description = "有效时间")
    private Long expireTime;

    @Schema(description = "状态 1有效 0无效")
    private Integer status;

    @Schema(description = "访问量")
    private Integer pv;


}
