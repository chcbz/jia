package cn.jia.wx.entity;

import cn.jia.core.entity.BaseEntity;
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
@TableName("wx_pay_info")
@Schema(name = "PayInfo对象")
public class PayInfoEntity extends BaseEntity {

    @TableId(value = "acid", type = IdType.AUTO)
    private Long acid;

    @Schema(description = "应用标识符")
    private String clientId;

    @Schema(description = "公众号名称")
    private String name;

    @Schema(description = "公众号帐号")
    private String account;

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
    private String appId;

    @Schema(description = "子商户公众账号ID")
    private String subAppId;

    @Schema(description = "商户号")
    private String mchId;

    @Schema(description = "商户密钥")
    private String mchKey;

    @Schema(description = "子商户号")
    private String subMchId;

    @Schema(description = "回调地址")
    private String notifyUrl;

    @Schema(description = "交易类型 JSAPI--公众号支付 NATIVE--原生扫码支付 APP--app支付")
    private String tradeType;

    @Schema(description = "签名方式 HMAC_SHA256和MD5")
    private String signType;

    @Schema(description = "证书文件路径")
    private String keyPath;

    @Schema(description = "证书文件内容")
    private String keyContent;

}
