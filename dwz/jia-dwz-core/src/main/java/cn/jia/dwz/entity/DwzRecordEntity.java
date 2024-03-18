package cn.jia.dwz.entity;

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
 * @since 2021-10-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("dwz_record")
@Schema(name = "DwzRecord对象")
public class DwzRecordEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 2452581299757065943L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "原地址")
    private String orig;

    @Schema(description = "目标地址")
    private String uri;

    @Schema(description = "有效时间")
    private Long expireTime;

    @Schema(description = "状态 1有效 0无效")
    private Integer status;

    @Schema(description = "访问量")
    private Integer pv;


}
