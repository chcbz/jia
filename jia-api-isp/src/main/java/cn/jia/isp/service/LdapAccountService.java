package cn.jia.isp.service;

import java.util.List;

import cn.jia.isp.entity.LdapAccount;

public interface LdapAccountService {

	public LdapAccount create(LdapAccount person);

	public LdapAccount findByUid(String uid);

	public LdapAccount modifyLdapAccount(LdapAccount person);

	public void deleteLdapAccount(LdapAccount person);
	
	public List<LdapAccount> findAll();
}
