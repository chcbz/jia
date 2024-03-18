package cn.jia.sms.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("sms_reply")
@Schema(name = "SmsReply对象", description="")
public class SmsReplyEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String msgid;

    private String mobile;

    private String xh;

    private String content;


}
