package cn.jia.wx.entity;

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
 * @since 2021-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("wx_mp_info")
@Schema(name = "MpInfo对象")
public class MpInfo extends BaseEntity {

    @TableId(value = "acid", type = IdType.AUTO)
    private Integer acid;

    @Schema(description = "应用标识码")
    private String clientId;

    @Schema(description = "令牌")
    private String token;

    private String accessToken;

    @Schema(description = "消息加解密密钥")
    private String encodingaeskey;

    @Schema(description = "认证类别 1普通订阅号 2普通服务号 3认证订阅号 4认证服务号/认证媒体/政府订阅号")
    private Integer level;

    @Schema(description = "公众号名称")
    private String name;

    @Schema(description = "公众号帐号")
    private String account;

    @Schema(description = "原始ID")
    private String original;

    @Schema(description = "介绍")
    private String signature;

    @Schema(description = "国家")
    private String country;

    @Schema(description = "省份")
    private String province;

    @Schema(description = "城市")
    private String city;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "登录密码")
    private String password;

    @Schema(description = "状态 1有效 0无效")
    private Integer status;

    @Schema(description = "开发者ID")
    private String appid;

    @Schema(description = "开发者密码")
    private String secret;

    private Integer styleid;

    private String subscribeurl;

    private String authRefreshToken;


}
