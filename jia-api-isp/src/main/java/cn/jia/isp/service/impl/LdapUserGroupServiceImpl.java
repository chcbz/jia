package cn.jia.isp.service.impl;

import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.service.LdapUserGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

@Service
public class LdapUserGroupServiceImpl implements LdapUserGroupService {

	@Autowired
	private LdapTemplate ldapTemplate;

	@Override
	public LdapUserGroup create(LdapUserGroup group) {
		ldapTemplate.create(group);
		return group;
	}

	@Override
	public LdapUserGroup findByCn(String cn) {
		return ldapTemplate.findOne(query().base("ou=groups,dc=jia").where("cn").is(cn), LdapUserGroup.class);
	}

	@Override
	public LdapUserGroup findByClientId(String clientId) {
		return ldapTemplate.findOne(query().base("ou=groups,dc=jia").where("clientId").is(clientId), LdapUserGroup.class);
	}
	
	@Override
	public List<LdapUserGroup> findAll() {
		return ldapTemplate.findAll(LdapUserGroup.class);
	}

	@Override
	public LdapUserGroup modify(LdapUserGroup group) {
		ldapTemplate.update(group);
		return group;
	}

	@Override
	public void delete(LdapUserGroup group) {
		ldapTemplate.delete(group);
	}

}
