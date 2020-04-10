package cn.jia.oauth.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DataUtil;
import cn.jia.oauth.common.ErrorConstants;
import cn.jia.oauth.entity.LdapUser;
import cn.jia.oauth.service.LdapUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.ContainerCriteria;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

@Service
public class LdapUserServiceImpl implements LdapUserService {

	@Autowired
	private LdapTemplate ldapTemplate;

	@Override
	public LdapUser create(LdapUser user) {
		user.setUid(DataUtil.getUUID());
		ldapTemplate.create(user);
		return user;
	}

	@Override
	public LdapUser findByUid(String uid) throws Exception{
		try {
			return ldapTemplate.findOne(query().base("ou=users,dc=jia").where("uid").is(uid), LdapUser.class);
		} catch (Exception e) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
	}
	
	@Override
	public List<LdapUser> findAll() {
		return ldapTemplate.findAll(LdapUser.class);
	}

	@Override
	public LdapUser modifyLdapUser(LdapUser user) {
		ldapTemplate.update(user);
		return user;
	}

	@Override
	public void deleteLdapUser(LdapUser user) {
		ldapTemplate.delete(user);
	}

	@Override
	public LdapUser findByExample(LdapUser person) throws Exception {
		try {
			ContainerCriteria criteria = query().base("ou=users,dc=jia").where("objectClass").is("jiaPerson");
			if(person.getUid() != null) {
				criteria.and("uid").is(person.getUid());
			}
			else if(person.getCn() != null) {
				criteria.and("cn").is(person.getCn());
			}
			else if(person.getTelephoneNumber() != null) {
				criteria.and("telephoneNumber").is(person.getTelephoneNumber());
			}
			else if(person.getOpenid() != null) {
				criteria.and("openid").is(person.getOpenid());
			}
			else if(person.getEmail() != null) {
				criteria.and("email").is(person.getEmail());
			}
			return ldapTemplate.findOne(criteria, LdapUser.class);
		} catch (Exception e) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
	}

	/**
	 * 检索用户
	 */
	@Override
	public List<LdapUser> search(LdapUser person) {
		ContainerCriteria criteria = query().base("ou=users,dc=jia").where("objectClass").is("jiaPerson");
		ContainerCriteria subCriteria = query().where("uid").is("");
		if(person.getUid() != null) {
			subCriteria.or("uid").is(person.getUid());
		}
		if(person.getCn() != null) {
			subCriteria.or("cn").is(person.getCn());
		}
		if(person.getTelephoneNumber() != null) {
			subCriteria.or("telephoneNumber").is(person.getTelephoneNumber());
		}
		if(person.getOpenid() != null) {
			subCriteria.or("openid").is(person.getOpenid());
		}
		if(person.getEmail() != null) {
			subCriteria.or("email").is(person.getEmail());
		}
		if(person.getWeiboid() != null) {
			subCriteria.or("weiboid").is(person.getWeiboid());
		}
		if(person.getWeixinid() != null) {
			subCriteria.or("weixinid").is(person.getWeixinid());
		}
		criteria.and(subCriteria);
		criteria.and("uuid").not().isPresent();

		return ldapTemplate.find(criteria, LdapUser.class);
	}

}
