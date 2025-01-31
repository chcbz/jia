package cn.jia.isp.service;

import cn.jia.isp.entity.LdapUser;
import java.util.List;

/**
 * Ldap用户服务接口定义
 * 提供一系列操作Ldap用户的方法，包括创建、查询、修改和删除等
 */
public interface LdapUserService {

    /**
     * 创建一个新的Ldap用户
     *
     * @param person 待创建的Ldap用户对象，包含用户的相关信息
     * @return 创建成功后的Ldap用户对象
     */
    LdapUser create(LdapUser person);

    /**
     * 根据用户ID查找Ldap用户
     *
     * @param uid 用户ID，用于唯一标识一个用户
     * @return 如果找到，返回对应的Ldap用户对象；否则返回null
     */
    LdapUser findByUid(String uid);

    /**
     * 根据示例对象查找Ldap用户
     *
     * @param person 示例对象，包含待查找用户的某些属性
     * @return 如果找到匹配的用户，返回对应的Ldap用户对象；否则返回null
     */
    LdapUser findByExample(LdapUser person);

    /**
     * 搜索Ldap用户
     *
     * @param person 包含搜索条件的用户对象
     * @return 符合搜索条件的用户列表
     */
    List<LdapUser> search(LdapUser person);

    /**
     * 修改Ldap用户信息
     *
     * @param person 包含更新信息的用户对象
     * @return 更新后的用户对象
     */
    LdapUser modifyLdapUser(LdapUser person);

    /**
     * 删除Ldap用户
     *
     * @param person 待删除的用户对象
     */
    void deleteLdapUser(LdapUser person);

    /**
     * 查找所有Ldap用户
     *
     * @return 包含所有用户的列表
     */
    List<LdapUser> findAll();
}
