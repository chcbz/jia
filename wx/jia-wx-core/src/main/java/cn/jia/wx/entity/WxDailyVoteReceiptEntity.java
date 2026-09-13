package cn.jia.wx.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("wx_daily_vote_receipt")
public class WxDailyVoteReceiptEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String appid;
    private String messageKey;
    private String userKey;
    private String requestFingerprint;
    private Long questionId;
    private String processorToken;
    private String status;
    private Integer correct;
    private Integer pointAwarded;
    private String replyContent;
}
