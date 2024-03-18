package cn.jia.user.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.time.LocalDate;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("user_info")
@Schema(name = "Info对象", description="")
public class UserEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @Schema(description = "用户ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "微信openid")
    private String openid;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "电话")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "性别 1男性 2女性 0未知")
    private Integer sex;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "城市")
    private String city;

    @Schema(description = "国家")
    private String country;

    @Schema(description = "省份")
    private String province;

    @Schema(description = "纬度")
    private String latitude;

    @Schema(description = "经度")
    private String longitude;

    @Schema(description = "积分")
    private Integer point;

    @Schema(description = "推荐人Jia账号")
    private String referrer;

    @Schema(description = "出生日期")
    private LocalDate birthday;

    @Schema(description = "固定电话")
    private String tel;

    @Schema(description = "微信号")
    private String weixin;

    @Schema(description = "QQ号")
    private String qq;

    @Schema(description = "职位ID")
    private String position;

    @Schema(description = "状态 1在岗 2出差 0离岗")
    private Integer status;

    @Schema(description = "简短说明")
    private String remark;

    @Schema(description = "接收消息的方式（多个以逗号隔开） 1微信 2邮箱 3短信")
    private String msgType;

    @Schema(description = "订阅内容（多个以逗号隔开）")
    private String subscribe;


}
