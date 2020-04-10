package cn.jia.isp.service;

import cn.jia.core.ldap.LdapPage;
import cn.jia.isp.entity.LdapAccount;
import cn.jia.isp.entity.LdapAccountDTO;
import cn.jia.isp.entity.LdapGroup;
import cn.jia.isp.entity.LdapGroupDTO;

import java.util.List;

public interface LdapAccountService {

	LdapAccount create(LdapAccountDTO record) throws Exception;

	LdapAccount findByUid(Integer serverId, String uid);

	LdapAccount findByUidNumber(Integer serverId, Integer uidNumber);

	LdapAccount modifyLdapAccount(LdapAccountDTO record);

	void deleteLdapAccount(Integer serverId, String uid) throws Exception;

	void changePassword(Integer serverId, String uid, String password) throws Exception;
	
	List<LdapAccount> findAll(Integer serverId);

	LdapPage<LdapAccount> findAll(LdapAccountDTO example, String nextTag, Integer pageSize);

	LdapGroup findGroup(Integer serverId, String cn);

	LdapGroup findGroupByGidNumber(Integer serverId, Integer gidNumber);

	LdapGroup createGroup(LdapGroupDTO record) throws Exception;

	LdapGroup modifyGroup(LdapGroupDTO record);

	void deleteGroup(Integer serverId, String cn);

	List<LdapGroup> findAllGroup(Integer serverId);
}
