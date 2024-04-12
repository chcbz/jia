package cn.jia.isp.service;

import cn.jia.isp.entity.LdapUser;

import java.util.List;

public interface LdapUserService {

	LdapUser create(LdapUser person);

	LdapUser findByUid(String uid);
	
	LdapUser findByExample(LdapUser person);
	
	List<LdapUser> search(LdapUser person);
	
	LdapUser modifyLdapUser(LdapUser person);

	void deleteLdapUser(LdapUser person);
	
	List<LdapUser> findAll();
}
