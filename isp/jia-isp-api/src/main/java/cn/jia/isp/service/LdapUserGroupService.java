package cn.jia.isp.service;

import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.entity.LdapUserGroupDTO;

import java.util.List;

public interface LdapUserGroupService {

	LdapUserGroup create(LdapUserGroup group);

	LdapUserGroup findByCn(String cn);

	LdapUserGroup findByClientId(String clientId);

	LdapUserGroup modify(LdapUserGroup group);

	void delete(LdapUserGroup group);
	
	List<LdapUserGroupDTO> findAll();
}
