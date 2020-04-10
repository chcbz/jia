package cn.jia.isp.entity;

import lombok.Data;

import java.util.List;

/**
 * @author chcbz
 */
@Data
public class LdapUserGroupDTO extends LdapUserGroup {
    private List<String> users;
}
