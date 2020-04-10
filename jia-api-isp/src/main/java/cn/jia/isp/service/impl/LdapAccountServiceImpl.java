package cn.jia.isp.service.impl;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Service;

import cn.jia.isp.entity.LdapAccount;
import cn.jia.isp.service.LdapAccountService;

@Service
public class LdapAccountServiceImpl implements LdapAccountService {

	@Autowired
	private LdapTemplate ldapTemplate;

	@Override
	public LdapAccount create(LdapAccount user) {
		ldapTemplate.create(user);
		return user;
	}

	@Override
	public LdapAccount findByUid(String uid) {
		LdapAccount user = ldapTemplate.findOne(query().where("uid").is(uid), LdapAccount.class);
		return user;
	}
	
	@Override
	public List<LdapAccount> findAll() {
		return ldapTemplate.findAll(LdapAccount.class);
	}

	@Override
	public LdapAccount modifyLdapAccount(LdapAccount user) {
		ldapTemplate.update(user);
		return user;
	}

	@Override
	public void deleteLdapAccount(LdapAccount user) {
		ldapTemplate.delete(user);
	}

}
