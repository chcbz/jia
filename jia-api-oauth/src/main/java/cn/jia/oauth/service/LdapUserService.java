package cn.jia.oauth.service;

import cn.jia.oauth.entity.LdapUser;

import java.util.List;

public interface LdapUserService {

	LdapUser create(LdapUser person);

	LdapUser findByUid(String uid) throws Exception;
	
	LdapUser findByExample(LdapUser person) throws Exception;
	
	List<LdapUser> search(LdapUser person);
	
	LdapUser modifyLdapUser(LdapUser person);

	void deleteLdapUser(LdapUser person);
	
	List<LdapUser> findAll();
}
