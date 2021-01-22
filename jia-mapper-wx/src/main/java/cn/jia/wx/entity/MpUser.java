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
@TableName("wx_mp_user")
@ApiModel(value="MpUser对象")
public class MpUser extends BaseEntity {

    @ApiModelProperty(value = "用户ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "应用标识码")
    private String clientId;

    @ApiModelProperty(value = "开发者ID")
    private String appid;

    @ApiModelProperty(value = "是否订阅")
    private Integer subscribe;

    @ApiModelProperty(value = "微信openid")
    private String openId;

    @ApiModelProperty(value = "Jia账号")
    private String jiacn;

    @ApiModelProperty(value = "电话")
    private Long subscribeTime;

    @ApiModelProperty(value = "邮箱")
    private String email;

    @ApiModelProperty(value = "性别 1男性 2女性 0未知")
    private Integer sex;

    @ApiModelProperty(value = "语言")
    private String language;

    @ApiModelProperty(value = "昵称")
    private String nickname;

    @ApiModelProperty(value = "头像")
    private String headImgUrl;

    @ApiModelProperty(value = "城市")
    private String city;

    @ApiModelProperty(value = "国家")
    private String country;

    @ApiModelProperty(value = "省份")
    private String province;

    @ApiModelProperty(value = "开放平台帐号")
    private String unionId;

    @ApiModelProperty(value = "组ID")
    private Integer groupId;

    @ApiModelProperty(value = "关注的渠道来源")
    private String subscribeScene;

    @ApiModelProperty(value = "二维码扫码场景")
    private String qrScene;

    @ApiModelProperty(value = "二维码扫码场景描述")
    private String qrSceneStr;

    @ApiModelProperty(value = "订阅服务明细")
    private String subscribeItems;

    @ApiModelProperty(value = "状态 1有效 0无效")
    private Integer status;

    @ApiModelProperty(value = "简短说明")
    private String remark;

}
