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
@TableName("wx_mp_user")
@Schema(name = "MpUser对象")
public class MpUserEntity extends BaseEntity {

    @Schema(description = "用户ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "应用标识码")
    private String clientId;

    @Schema(description = "开发者ID")
    private String appid;

    @Schema(description = "是否订阅")
    private Integer subscribe;

    @Schema(description = "微信openid")
    private String openId;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "电话")
    private Long subscribeTime;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "性别 1男性 2女性 0未知")
    private Integer sex;

    @Schema(description = "语言")
    private String language;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像")
    private String headImgUrl;

    @Schema(description = "城市")
    private String city;

    @Schema(description = "国家")
    private String country;

    @Schema(description = "省份")
    private String province;

    @Schema(description = "开放平台帐号")
    private String unionId;

    @Schema(description = "组ID")
    private Integer groupId;

    @Schema(description = "关注的渠道来源")
    private String subscribeScene;

    @Schema(description = "二维码扫码场景")
    private String qrScene;

    @Schema(description = "二维码扫码场景描述")
    private String qrSceneStr;

    @Schema(description = "订阅服务明细")
    private String subscribeItems;

    @Schema(description = "状态 1有效 0无效")
    private Integer status;

    @Schema(description = "简短说明")
    private String remark;

}
