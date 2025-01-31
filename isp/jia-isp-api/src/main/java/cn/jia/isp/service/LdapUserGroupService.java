package cn.jia.isp.service;

import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.entity.LdapUserGroupDTO;
import java.util.List;

/**
 * LdapUserGroupService接口定义了对LDAP用户组进行操作的方法
 * 它提供了创建、查找、修改和删除LDAP用户组的功能，以及获取所有用户组的列表
 */
public interface LdapUserGroupService {

    /**
     * 创建一个新的LDAP用户组
     *
     * @param group 待创建的LDAP用户组对象，包含用户组的相关信息
     * @return 创建成功后的LDAP用户组对象，包括系统生成的唯一标识
     */
    LdapUserGroup create(LdapUserGroup group);

    /**
     * 根据用户组的CN（Common Name）查找LDAP用户组
     *
     * @param cn 用户组的CN值，用于唯一标识一个用户组
     * @return 匹配CN的LDAP用户组对象，如果找不到则返回null
     */
    LdapUserGroup findByCn(String cn);

    /**
     * 根据客户端ID查找LDAP用户组
     *
     * @param clientId 客户端ID，用于标识请求来源
     * @return 匹配客户端ID的LDAP用户组对象，如果找不到则返回null
     */
    LdapUserGroup findByClientId(String clientId);

    /**
     * 修改LDAP用户组信息
     *
     * @param group 包含更新信息的LDAP用户组对象
     * @return 更新后的LDAP用户组对象
     */
    LdapUserGroup modify(LdapUserGroup group);

    /**
     * 删除LDAP用户组
     *
     * @param group 待删除的LDAP用户组对象
     */
    void delete(LdapUserGroup group);

    /**
     * 获取所有LDAP用户组的列表
     *
     * @return 包含所有LDAP用户组的列表，以DTO形式返回
     */
    List<LdapUserGroupDTO> findAll();
}
