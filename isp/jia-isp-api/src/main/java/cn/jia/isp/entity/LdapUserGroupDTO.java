package cn.jia.isp.entity;

import cn.jia.isp.entity.LdapUserGroup;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @author chcbz
 */
@Getter
@Setter
public class LdapUserGroupDTO extends LdapUserGroup {
    private List<String> users;
}
