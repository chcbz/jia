package cn.jia.isp.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("car_brand")
@Schema(name = "CarBrand对象")
public class CarBrandEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String clientId;

    private String brand;

    private String abbr;

    private String initials;

    private Long addTime;

    private Long upTime;
}