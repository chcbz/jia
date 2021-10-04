package cn.jia.core.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-10-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("core_log")
@ApiModel(value="Log对象", description="")
public class Log extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "Jia账号")
    private String jiacn;

    private String username;

    @ApiModelProperty(value = "IP地址")
    private String ip;

    @ApiModelProperty(value = "访问地址")
    private String uri;

    private String method;

    @ApiModelProperty(value = "请求参数")
    private String param;

    @ApiModelProperty(value = "客户端信息")
    private String userAgent;

    @ApiModelProperty(value = "请求头信息")
    private String header;

    @ApiModelProperty(value = "记录时间")
    private Long time;


}
