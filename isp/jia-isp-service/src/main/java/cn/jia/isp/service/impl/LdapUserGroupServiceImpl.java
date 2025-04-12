package cn.jia.isp.service.impl;

import cn.jia.core.util.CollectionUtil;
import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.entity.LdapUserGroupDTO;
import cn.jia.isp.service.LdapUserGroupService;
import cn.jia.isp.vomapper.LdapUserGroupMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.SneakyThrows;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import javax.naming.Name;
import java.util.List;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

@Named
public class LdapUserGroupServiceImpl implements LdapUserGroupService {

	@Inject
	private LdapTemplate ldapTemplate;

	@Override
	public LdapUserGroup create(LdapUserGroup group) {
		ldapTemplate.create(group);
		return group;
	}

	@Override
	public LdapUserGroup findByCn(String cn) {
		return CollectionUtil.findFirst(ldapTemplate.find(
				query().base("ou=groups").where("cn").is(cn), LdapUserGroup.class));
	}

	@Override
	public LdapUserGroup findByClientId(String clientId) {
		return CollectionUtil.findFirst(ldapTemplate.find(
				query().base("ou=groups").where("clientId").is(clientId), LdapUserGroup.class));
	}
	
	@Override
	public List<LdapUserGroupDTO> findAll() {
		return LdapUserGroupMapper.INSTANCE.toList(ldapTemplate.findAll(LdapUserGroup.class));
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

	@Override
	@SneakyThrows
    public LdapUserGroup addUser(LdapUserGroup group, Name userDn) {
		LdapContextSource contextSource = (LdapContextSource) ldapTemplate.getContextSource();
		group.getMember().add(contextSource.getBaseLdapName().addAll(userDn));
		return modify(group);
	}
}
