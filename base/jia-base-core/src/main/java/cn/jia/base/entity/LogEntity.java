package cn.jia.base.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * <p>
 * 日志
 * </p>
 *
 * @author chc
 * @since 2023-06-18
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("CORE_LOG")
@Schema(name = "Log", description = "日志")
public class LogEntity extends BaseEntity {

    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "Jia账号")
    private String jiacn;

    private String username;

    @Schema(description = "IP地址")
    private String ip;

    @Schema(description = "访问地址")
    private String uri;

    private String method;

    @Schema(description = "请求参数")
    private String param;

    @Schema(description = "客户端信息")
    private String userAgent;

    @Schema(description = "请求头信息")
    private String header;
}
