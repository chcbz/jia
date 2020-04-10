package cn.jia.isp.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.ldap.LdapPage;
import cn.jia.core.util.Base64Util;
import cn.jia.core.util.StringUtils;
import cn.jia.isp.common.ErrorConstants;
import cn.jia.isp.dao.IspServerMapper;
import cn.jia.isp.entity.*;
import cn.jia.isp.service.IspService;
import cn.jia.isp.service.LdapAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.control.PagedResultsCookie;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.Filter;
import org.springframework.ldap.filter.WhitespaceWildcardsFilter;
import org.springframework.ldap.odm.core.ObjectDirectoryMapper;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.stereotype.Service;

import javax.naming.directory.SearchControls;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

@Service
public class LdapAccountServiceImpl implements LdapAccountService {
	
	@Autowired
	private IspService ispService;
	@Autowired
	private IspServerMapper ispServerMapper;

	private Map<Integer, LdapTemplate> ldapTemplateMap;
	
	private LdapTemplate findLdapTemplate(Integer id) {
		if(ldapTemplateMap == null){
			ldapTemplateMap = new HashMap<>();
		}
		LdapTemplate ldapTemplate = ldapTemplateMap.get(id);
		if(ldapTemplate == null) {
			IspServer server = ispServerMapper.selectByPrimaryKey(id);
			LdapContextSource contextSource = new LdapContextSource();
			Map<String, Object> config = new HashMap<>();

			contextSource.setUrl("ldap://" + server.getSmbLdapUrl());
			contextSource.setBase(server.getSmbLdapBase());
			contextSource.setUserDn("cn=" + server.getSmbLdapUser() + "," + server.getSmbLdapBase());
			contextSource.setPassword(server.getSmbLdapPassword());

			//  解决 乱码 的关键一句
			config.put("java.naming.ldap.attributes.binary", "objectGUID");

			contextSource.setPooled(true);
			contextSource.setBaseEnvironmentProperties(config);
			contextSource.afterPropertiesSet();

			ldapTemplate = new LdapTemplate(contextSource);
			ldapTemplateMap.put(id, ldapTemplate);
		}
		return ldapTemplate;
	}

	@Override
	public LdapAccount create(LdapAccountDTO record) throws Exception {
	    if(record == null || StringUtils.isEmpty(record.getUid()) || StringUtils.isEmpty(record.getCn()) ||
            record.getUserPassword() == null || record.getGidNumber() == null) {
	        throw new EsRuntimeException(ErrorConstants.PARAMETER_INCORRECT);
        }
		IspServer server = ispService.findServer(record.getServerId());
	    if(server == null) {
	        throw new EsRuntimeException(ErrorConstants.ISP_CONN_FAILD);
        }
		ispService.execCommand(record.getServerId(), "/home/isp/bin/samba.sh useradd " + record.getUid() + " " + record.getUserPassword() + " " + record.getCn() + " " + record.getGidNumber() + " " + server.getSmbLdapPassword());
		return findLdapTemplate(record.getServerId()).findOne(query().where("uid").is(record.getUid()), LdapAccount.class);
	}

	@Override
	public LdapAccount findByUid(Integer serverId, String uid) {
		return findLdapTemplate(serverId).findOne(query().where("uid").is(uid), LdapAccount.class);
	}

	@Override
	public LdapAccount findByUidNumber(Integer serverId, Integer uidNumber) {
		return findLdapTemplate(serverId).findOne(query().where("uidNumber").is(String.valueOf(uidNumber)), LdapAccount.class);
	}
	
	@Override
	public List<LdapAccount> findAll(Integer serverId) {
		return findLdapTemplate(serverId).findAll(LdapAccount.class);
	}

	@Override
	public LdapPage<LdapAccount> findAll(LdapAccountDTO example, String nextTag, Integer pageSize) {
		PagedResultsCookie cookie = new PagedResultsCookie(StringUtils.isEmpty(nextTag) ? null : Base64Util.decode(nextTag));
		LdapTemplate ldapTemplate = findLdapTemplate(example.getServerId());
		Class<LdapAccount> clazz = LdapAccount.class;
		SearchControls searchControls = new SearchControls();
		searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
		ObjectDirectoryMapper odm = ldapTemplate.getObjectDirectoryMapper();
		AndFilter filter = new AndFilter();
		if(StringUtils.isNotEmpty(example.getCn())){
			filter.and(new WhitespaceWildcardsFilter("cn", example.getCn()));
		}
		if(example.getGidNumber() != null) {
			filter.and(new EqualsFilter("gidNumber", example.getGidNumber()));
		}

		Filter finalFilter = odm.filterFor(clazz, filter);

		PagedResultsDirContextProcessor processor = new PagedResultsDirContextProcessor(pageSize, cookie);

		List<LdapAccount> list = ldapTemplate.search(LdapUtils.emptyLdapName(), finalFilter.encode(), searchControls, (ContextMapper<LdapAccount>) (ctx -> odm.mapFromLdapDataEntry((DirContextOperations) ctx, clazz)), processor);
		LdapPage<LdapAccount> result = new LdapPage<>();
		result.addAll(list);
		if(processor.getCookie().getCookie() != null){
			result.setNextTag(Base64Util.encode(processor.getCookie().getCookie()));
		}
		result.setMore(processor.hasMore());
		result.setPageSize(pageSize);
		result.setTotal(processor.getResultSize());
		return result;
	}

	@Override
	public LdapGroup findGroup(Integer serverId, String cn) {
		return findLdapTemplate(serverId).findOne(query().where("cn").is(cn), LdapGroup.class);
	}

	@Override
	public LdapGroup findGroupByGidNumber(Integer serverId, Integer gidNumber) {
		return findLdapTemplate(serverId).findOne(query().where("gidNumber").is(String.valueOf(gidNumber)), LdapGroup.class);
	}

	@Override
	public LdapGroup createGroup(LdapGroupDTO record) throws Exception {
		IspServer server = ispService.findServer(record.getServerId());
		if(server == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		ispService.execCommand(record.getServerId(), "/home/isp/bin/samba.sh groupadd " + record.getCn() + " " + server.getSmbLdapPassword() + " " + record.getDescription());
		return findLdapTemplate(record.getServerId()).findOne(query().where("cn").is(record.getCn()), LdapGroup.class);
	}

	@Override
	public LdapGroup modifyGroup(LdapGroupDTO record) {
		LdapGroup group = findGroup(record.getServerId(), record.getCn());
		if(StringUtils.isNotEmpty(record.getDescription())) {
			group.setDescription(record.getDescription());
		}
		findLdapTemplate(record.getServerId()).update(group);
		return group;
	}

	@Override
	public void deleteGroup(Integer serverId, String cn) {
		LdapGroup record = findGroup(serverId, cn);
		findLdapTemplate(serverId).delete(record);
	}

	@Override
	public List<LdapGroup> findAllGroup(Integer serverId) {
		return findLdapTemplate(serverId).findAll(LdapGroup.class);
	}

	@Override
	public LdapAccount modifyLdapAccount(LdapAccountDTO record) {
		LdapAccount account = findByUid(record.getServerId(), record.getUid());
		if(StringUtils.isNotEmpty(record.getCn())) {
			account.setCn(record.getCn());
		}
		if(record.getGidNumber() != null) {
			account.setGidNumber(record.getGidNumber());
		}
		if(record.getRadiusUserPassword() != null) {
			account.setRadiusUserPassword(record.getRadiusUserPassword());
		}
		findLdapTemplate(record.getServerId()).update(account);
		return account;
	}

	@Override
	public void deleteLdapAccount(Integer serverId, String uid) throws Exception {
		IspServer server = ispService.findServer(serverId);
		ispService.execCommand(serverId, "/home/isp/bin/samba.sh userdel " + uid + " " + server.getSmbLdapPassword());
	}

	@Override
	public void changePassword(Integer serverId, String uid, String password) throws Exception {
		LdapAccount account = findByUid(serverId, uid);
		ispService.execCommand(serverId, "/home/isp/bin/samba.sh userpwd " + uid + " " + password + " " + account.getGidNumber());
	}
}
