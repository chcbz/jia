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
@TableName("wx_pay_info")
@ApiModel(value="PayInfo对象")
public class PayInfo extends BaseEntity {

    @TableId(value = "acid", type = IdType.AUTO)
    private Integer acid;

    @ApiModelProperty(value = "应用标识码")
    private String clientId;

    @ApiModelProperty(value = "公众号名称")
    private String name;

    @ApiModelProperty(value = "公众号帐号")
    private String account;

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
    private String appId;

    @ApiModelProperty(value = "子商户公众账号ID")
    private String subAppId;

    @ApiModelProperty(value = "商户号")
    private String mchId;

    @ApiModelProperty(value = "商户密钥")
    private String mchKey;

    @ApiModelProperty(value = "子商户号")
    private String subMchId;

    @ApiModelProperty(value = "回调地址")
    private String notifyUrl;

    @ApiModelProperty(value = "交易类型 JSAPI--公众号支付 NATIVE--原生扫码支付 APP--app支付")
    private String tradeType;

    @ApiModelProperty(value = "签名方式 HMAC_SHA256和MD5")
    private String signType;

    @ApiModelProperty(value = "证书文件路径")
    private String keyPath;

    @ApiModelProperty(value = "证书文件内容")
    private String keyContent;

}
