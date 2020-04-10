package cn.jia.isp.api;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.BeanUtil;
import cn.jia.isp.common.ErrorConstants;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.entity.LdapUserGroupDTO;
import cn.jia.isp.service.LdapUserGroupService;
import cn.jia.isp.service.LdapUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.naming.Name;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/ldap")
public class LdapController {
	
	@Autowired
	private LdapUserService ldapUserService;
	
	@Autowired
	private LdapUserGroupService ldapUserGroupService;

	@Autowired
	private LdapTemplate ldapTemplate;

	/**
	 * 根据cn获取用户信息
	 * @param type 检索类型
	 * @param key 检索值
	 * @return 用户信息
	 * @throws Exception 异常情况
	 */
	@PreAuthorize("hasAuthority('ldap-user_get')")
	@RequestMapping(value = "/user/get", method = RequestMethod.GET)
	public Object userFind(@RequestParam(name = "type", defaultValue = "uid") String type, @RequestParam(name = "key") String key) throws Exception {
		LdapUser user = new LdapUser();
		if("uid".equals(type)) {
			user.setUid(key);
		}else if("openid".equals(type)) {
			user.setOpenid(key);
		}else if("phone".equals(type)) {
			user.setTelephoneNumber(key);
		}else if("email".equals(type)) {
			user.setEmail(key);
		}
		user = ldapUserService.findByExample(user);
		if(user == null) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
		user.setUserPassword("***".getBytes());
		return JSONResult.success(user);
	}
	
	/**
	 * 根据条件检索用户，只要其中一个匹配即可
	 * @param user 检索条件
	 * @return 用户信息
	 * @throws Exception 异常信息
	 */
	@PreAuthorize("hasAuthority('ldap-user_search')")
	@RequestMapping(value = "/user/search", method = RequestMethod.POST)
	public Object userFind(@RequestBody LdapUser user) throws Exception {
		List<LdapUser> userList = ldapUserService.search(user);
		if(userList == null || userList.size() == 0) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
		return JSONResult.success(userList);
	}

	/**
	 * 创建用户
	 * @param user 用户信息
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('ldap-user_create')")
	@RequestMapping(value = "/user/create", method = RequestMethod.POST)
	public Object userCreate(@RequestBody LdapUser user) {
		ldapUserService.create(user);
		return JSONResult.success();
	}

	/**
	 * 更新用户信息
	 * @param user 用户信息
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('ldap-user_update')")
	@RequestMapping(value = "/user/update", method = RequestMethod.POST)
	public Object userUpdate(@RequestBody LdapUser user) throws Exception {
		LdapUser upUser = ldapUserService.findByUid(user.getUid());
		BeanUtil.copyPropertiesIgnoreNull(user, upUser);
		ldapUserService.modifyLdapUser(upUser);
		return JSONResult.success();
	}

	/**
	 * 删除用户
	 * @param uid 用户ID
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('ldap-user_delete')")
	@RequestMapping(value = "/user/delete", method = RequestMethod.GET)
	public Object userDelete(@RequestParam(name = "uid") String uid) {
		LdapUser user = new LdapUser();
		user.setUid(uid);
		ldapUserService.deleteLdapUser(user);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有用户信息
	 * @return 用户列表
	 */
	@PreAuthorize("hasAuthority('ldap-user_findAll')")
	@RequestMapping(value = "/user/findAll", method = RequestMethod.GET)
	public Object userFindAll() {
		List<LdapUser> userList = ldapUserService.findAll();
		JSONResultPage result = new JSONResultPage<>(userList);
		result.setTotal((long)userList.size());
		return result;
	}
	
	/**
	 * 根据cn查找用户组信息
	 * @param cn 组ID
	 * @return 组信息
	 */
	@PreAuthorize("hasAuthority('ldap-usergroup_findByCn')")
	@RequestMapping(value = "/usergroup/findByCn", method = RequestMethod.GET)
	public Object userGroupFindByCn(@RequestParam(name = "cn") String cn) {
		LdapUserGroup user = ldapUserGroupService.findByCn(cn);
		return JSONResult.success(user);
	}
	
	/**
	 * 查找所有用户组信息
	 * @return 用户组列表
	 */
	@PreAuthorize("hasAuthority('ldap-usergroup_findAll')")
	@RequestMapping(value = "/usergroup/findAll", method = RequestMethod.GET)
	public Object userGroupFindAll() {
		List<LdapUserGroup> userGroupList = ldapUserGroupService.findAll();
		JSONResultPage result = new JSONResultPage<>(userGroupList);
		result.setTotal((long)userGroupList.size());
		return result;
	}
	
	/**
	 * 创建用户组
	 * @param userGroup 用户组信息
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('ldap-usergroup_create')")
	@RequestMapping(value = "/usergroup/create", method = RequestMethod.POST)
	public Object userGroupCreate(@RequestBody LdapUserGroupDTO userGroup) throws Exception {
		LdapContextSource contextSource = (LdapContextSource) ldapTemplate.getContextSource();
		Name baseDn = contextSource.getBaseLdapName();
		Set<Name> member = new HashSet<>();
		for(String uid : userGroup.getUsers()){
			LdapUser user = ldapUserService.findByUid(uid);
			if(user == null){
				throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
			}
			member.add(baseDn.addAll(user.getDn()));
		}
		userGroup.setMember(member);
		ldapUserGroupService.create(userGroup);
		return JSONResult.success();
	}

	/**
	 * 更新用户组信息
	 * @param userGroup 用户组信息
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('ldap-usergroup_update')")
	@RequestMapping(value = "/usergroup/update", method = RequestMethod.POST)
	public Object userGroupUpdate(@RequestBody LdapUserGroup userGroup) {
		LdapUserGroup upUserGroup = ldapUserGroupService.findByCn(userGroup.getCn());
		BeanUtil.copyPropertiesIgnoreNull(userGroup, upUserGroup);
		ldapUserGroupService.modify(upUserGroup);
		return JSONResult.success();
	}
	
	/**
	 * 将用户添加到用户组
	 * @param userGroup 用户组信息
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('ldap-usergroup_member_add')")
	@RequestMapping(value = "/usergroup/member/add", method = RequestMethod.POST)
	public Object userGroupMemberAdd(@RequestBody LdapUserGroupDTO userGroup) throws Exception {
		LdapUserGroup upUserGroup = ldapUserGroupService.findByCn(userGroup.getCn());

		LdapContextSource contextSource = (LdapContextSource) ldapTemplate.getContextSource();
		Name baseDn = contextSource.getBaseLdapName();
		Set<Name> member = upUserGroup.getMember();
		for(String uid : userGroup.getUsers()){
			LdapUser user = ldapUserService.findByUid(uid);
			if(user == null){
				throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
			}
			member.add(baseDn.addAll(user.getDn()));
		}
		upUserGroup.setMember(member);
		ldapUserGroupService.modify(upUserGroup);
		return JSONResult.success();
	}
	
	/**
	 * 将用户从用户组中删除
	 * @param userGroup 用户组信息
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('ldap-usergroup_member_delete')")
	@RequestMapping(value = "/usergroup/member/delete", method = RequestMethod.POST)
	public Object userGroupMemberDelete(@RequestBody LdapUserGroupDTO userGroup) throws Exception {
		LdapUserGroup upUserGroup = ldapUserGroupService.findByCn(userGroup.getCn());
		LdapContextSource contextSource = (LdapContextSource) ldapTemplate.getContextSource();
		Name baseDn = contextSource.getBaseLdapName();
		Set<Name> member = upUserGroup.getMember();
		for(String uid : userGroup.getUsers()){
			LdapUser user = ldapUserService.findByUid(uid);
			if(user == null){
				throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
			}
			member.remove(baseDn.addAll(user.getDn()));
		}
		ldapUserGroupService.modify(upUserGroup);
		return JSONResult.success();
	}

	/**
	 * 删除用户组
	 * @param cn 用户组ID
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('ldap-usergroup_delete')")
	@RequestMapping(value = "/usergroup/delete", method = RequestMethod.GET)
	public Object userGroupDelete(@RequestParam(name = "cn") String cn) {
		LdapUserGroup userGroup = new LdapUserGroup();
		userGroup.setCn(cn);
		ldapUserGroupService.delete(userGroup);
		return JSONResult.success();
	}
}
