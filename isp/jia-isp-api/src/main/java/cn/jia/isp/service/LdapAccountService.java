package cn.jia.isp.service;

import cn.jia.isp.entity.LdapAccount;
import java.util.List;

/**
 * LdapAccountService接口定义了对LDAP账户进行操作的方法
 * 它提供了创建、查询、修改和删除LDAP账户的功能
 */
public interface LdapAccountService {

    /**
     * 创建一个新的LDAP账户
     *
     * @param person 包含要创建的LDAP账户信息的对象
     * @return 创建成功后的新LDAP账户对象
     */
    LdapAccount create(LdapAccount person);

    /**
     * 根据UID查询LDAP账户
     *
     * @param uid 要查询的LDAP账户的唯一标识符
     * @return 匹配UID的LDAP账户对象，如果不存在则返回null
     */
    LdapAccount findByUid(String uid);

    /**
     * 修改LDAP账户信息
     *
     * @param person 包含要修改的LDAP账户新信息的对象
     * @return 修改成功后的LDAP账户对象
     */
    LdapAccount modifyLdapAccount(LdapAccount person);

    /**
     * 删除LDAP账户
     *
     * @param person 包含要删除的LDAP账户信息的对象
     */
    void deleteLdapAccount(LdapAccount person);

    /**
     * 查询所有LDAP账户
     *
     * @return 包含所有LDAP账户的列表
     */
    List<LdapAccount> findAll();
}
