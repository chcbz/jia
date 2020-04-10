package cn.jia.isp.service;

import cn.jia.isp.entity.LdapUser;

import java.util.List;

public interface LdapUserService {

	LdapUser create(LdapUser person);

	LdapUser findByUid(String uid) throws Exception;
	
	LdapUser findByExample(LdapUser person) throws Exception;
	
	List<LdapUser> search(LdapUser person) throws Exception;
	
	LdapUser modifyLdapUser(LdapUser person);

	void deleteLdapUser(LdapUser person);
	
	List<LdapUser> findAll();
}
