package cn.jia.isp.entity;

import lombok.Getter;
import lombok.Setter;

import javax.naming.Name;
import java.util.List;

/**
 * @author chcbz
 */
@Getter
@Setter
public class LdapUserGroupDTO {
    private Name dn;

    private String cn;

    private String clientId;

    private String name;

    private byte[] logo;

    private byte[] logoIcon;

    private String remark;

    private String description;

    private List<String> users;
}
