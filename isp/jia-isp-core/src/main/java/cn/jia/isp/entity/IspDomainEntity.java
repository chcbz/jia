package cn.jia.isp.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("isp_domain")
@Schema(name = "IspDomain对象")
public class IspDomainEntity extends BaseEntity {
    @TableId(value = "no", type = IdType.AUTO)
    private Long no;

    private String clientId;

    private Long serverId;

    private String domainName;

    private String dnsType;

    private String dnsKey;

    private String dnsToken;

    private Integer sslFlag;

    private String adminPasswd;

    private Integer adminFlag;

    private Integer mailboxService;

    private Integer mailboxCount;

    private Integer mailboxQuota;

    private Integer hostService;

    private String hostType;

    private String hostPasswd;

    private Integer hostQuota;

    private Integer sqlService;

    private String sqlPasswd;

    private Integer sqlQuota;

    private String ftpDir;

    private Integer cmsFlag;

}