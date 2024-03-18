package cn.jia.isp.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("dns_record_history")
@Schema(name = "DnsRecordHistory对象")
public class IspDnsRecordHistoryEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long zoneId;

    private String domain;

    private Integer ttl;

    private String type;

    private String ip;

    private Date recordTime;

}