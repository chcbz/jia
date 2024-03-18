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
@TableName("isp_server")
@Schema(name = "IspServer对象")
public class IspServerEntity extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String clientId;

    private String serverName;

    private String serverDescription;

    private String ip;

    private Integer sshPort;

    private String sshUser;

    private String sshPassword;

    private Integer consolePort;

    private String consoleToken;

    private Integer ldapService;

    private Integer ldapPort;

    private String ldapUser;

    private String ldapPassword;

    private String ldapBase;

    private Integer smbService;

    private String smbLdapBase;

    private String smbLdapUser;

    private String smbLdapPassword;

    private String smbLdapUrl;

    private Integer status;

    private Long createTime;

    private Long updateTime;

}