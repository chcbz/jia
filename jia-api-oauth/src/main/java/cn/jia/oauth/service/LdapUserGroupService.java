package cn.jia.oauth.service;

import cn.jia.oauth.entity.LdapUserGroup;

import java.util.List;

public interface LdapUserGroupService {

	LdapUserGroup create(LdapUserGroup group);

	LdapUserGroup findByCn(String cn);

	LdapUserGroup findByClientId(String clientId);

	LdapUserGroup modify(LdapUserGroup group);

	void delete(LdapUserGroup group);
	
	List<LdapUserGroup> findAll();
}
