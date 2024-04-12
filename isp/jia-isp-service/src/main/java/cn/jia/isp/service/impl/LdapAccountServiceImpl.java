package cn.jia.isp.service.impl;

import cn.jia.core.util.CollectionUtil;
import cn.jia.isp.entity.LdapAccount;
import cn.jia.isp.service.LdapAccountService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.ldap.core.LdapTemplate;

import java.util.List;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

@Named
public class LdapAccountServiceImpl implements LdapAccountService {

	@Inject
	private LdapTemplate ldapTemplate;

	@Override
	public LdapAccount create(LdapAccount user) {
		ldapTemplate.create(user);
		return user;
	}

	@Override
	public LdapAccount findByUid(String uid) {
		return CollectionUtil.findFirst(ldapTemplate.find(
				query().where("uid").is(uid), LdapAccount.class));
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
