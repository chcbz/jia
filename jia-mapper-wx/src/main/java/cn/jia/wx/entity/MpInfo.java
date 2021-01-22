package cn.jia.wx.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
 * @since 2021-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("wx_mp_info")
@ApiModel(value="MpInfo对象")
public class MpInfo extends BaseEntity {

    @TableId(value = "acid", type = IdType.AUTO)
    private Integer acid;

    @ApiModelProperty(value = "应用标识码")
    private String clientId;

    @ApiModelProperty(value = "令牌")
    private String token;

    private String accessToken;

    @ApiModelProperty(value = "消息加解密密钥")
    private String encodingaeskey;

    @ApiModelProperty(value = "认证类别 1普通订阅号 2普通服务号 3认证订阅号 4认证服务号/认证媒体/政府订阅号")
    private Integer level;

    @ApiModelProperty(value = "公众号名称")
    private String name;

    @ApiModelProperty(value = "公众号帐号")
    private String account;

    @ApiModelProperty(value = "原始ID")
    private String original;

    @ApiModelProperty(value = "介绍")
    private String signature;

    @ApiModelProperty(value = "国家")
    private String country;

    @ApiModelProperty(value = "省份")
    private String province;

    @ApiModelProperty(value = "城市")
    private String city;

    @ApiModelProperty(value = "登录账号")
    private String username;

    @ApiModelProperty(value = "登录密码")
    private String password;

    @ApiModelProperty(value = "状态 1有效 0无效")
    private Integer status;

    @ApiModelProperty(value = "开发者ID")
    private String appid;

    @ApiModelProperty(value = "开发者密码")
    private String secret;

    private Integer styleid;

    private String subscribeurl;

    private String authRefreshToken;


}
