package cn.jia.user.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.jia.user.dao.GroupRelMapper;
import cn.jia.user.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.user.common.Constants;
import cn.jia.user.common.ErrorConstants;
import cn.jia.user.dao.OrgRelMapper;
import cn.jia.user.dao.RoleRelMapper;
import cn.jia.user.dao.UserMapper;
import cn.jia.user.service.LdapService;
import cn.jia.user.service.UserService;

@Service
public class UserServiceImpl implements UserService {
	
	@Autowired
	private UserMapper userMapper;
	@Autowired
	private RoleRelMapper roleRelMapper;
	@Autowired
	private OrgRelMapper orgRelMapper;
	@Autowired
	private LdapService ldapService;
	@Autowired
	private GroupRelMapper groupRelMapper;

	@Override
	public User create(User user) throws Exception {
		//查找LDAP服务器是否有该用户
		Map<String, Object> ldapUser = null;
		Map<String, Object> params = new HashMap<>();
		params.put("telephoneNumber", user.getPhone());
		params.put("email", user.getEmail());
		params.put("openid", user.getOpenid());
		
		JSONResult<List<Map<String, Object>>> ldapUserResult = ldapService.userFind(params);
		if(ErrorConstants.SUCCESS.equals(ldapUserResult.getCode())) {
			ldapUser = ldapUserResult.getData().get(0);
			user.setJiacn(String.valueOf(ldapUser.get("cn")));
		}
		//将用户添加到ldap服务器
		if(ldapUser == null) {
			params = new HashMap<>();
			String cn = StringUtils.isEmpty(user.getPhone()) ? (StringUtils.isEmpty(user.getEmail()) ? user.getOpenid() : user.getEmail()) : user.getPhone();
			params.put("cn", cn);
			params.put("sn", cn);
			params.put("telephoneNumber", StringUtils.isEmpty(user.getPhone()) ? null : user.getPhone());
			params.put("email", StringUtils.isEmpty(user.getEmail()) ? null : user.getEmail());
			params.put("openid", StringUtils.isEmpty(user.getOpenid()) ? null : user.getOpenid());
			params.put("country", StringUtils.isEmpty(user.getCountry()) ? null : user.getCountry());
			params.put("province", StringUtils.isEmpty(user.getProvince()) ? null : user.getProvince());
			params.put("city", StringUtils.isEmpty(user.getCity()) ? null : user.getCity());
			params.put("sex", user.getSex());
			params.put("nickname", StringUtils.isEmpty(user.getNickname()) ? null : user.getNickname());
			JSONResult<String> newUserResult = ldapService.userCreate(params);
			if(!ErrorConstants.SUCCESS.equals(newUserResult.getCode())) {
				throw new EsRuntimeException(ErrorConstants.DEFAULT_ERROR_CODE);
			}
			user.setJiacn(cn);
		}
		//查找本地数据库是否有该用户，如果没有则新增，如果有则更新
		User searchUser = new User();
		searchUser.setJiacn(user.getJiacn());
		searchUser.setPhone(StringUtils.isEmpty(user.getPhone()) ? null : user.getPhone());
		searchUser.setEmail(StringUtils.isEmpty(user.getEmail()) ? null : user.getEmail());
		searchUser.setOpenid(StringUtils.isEmpty(user.getOpenid()) ? null : user.getOpenid());
		Page<User> curUser = this.search(searchUser, 1, 1);
		if(curUser != null && curUser.getResult().size() > 0) {
			throw new EsRuntimeException(ErrorConstants.USER_HAS_EXIST);
		}else {
			Long now = DateUtil.genTime(new Date());
			user.setCreateTime(now);
			user.setUpdateTime(now);
			userMapper.insertSelective(user);
			//设置默认角色
			RoleRel rel = new RoleRel();
			rel.setRoleId(Constants.DEFAULT_ROLE_ID);
			rel.setUserId(user.getId());
			rel.setCreateTime(now);
			rel.setUpdateTime(now);
			roleRelMapper.insertSelective(rel);
		}
		return user;
	}

	@Override
	public User find(Integer id) {
		return userMapper.selectByPrimaryKey(id);
	}

	@Override
	public User update(User user) {
		Long now = DateUtil.genTime(new Date());
		user.setUpdateTime(now);
		userMapper.updateByPrimaryKeySelective(user);
		return user;
	}

	@Override
	public void delete(Integer id) {
		userMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<User> list(int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return userMapper.selectAll();
	}

	/**
	 * 增加或减少用户积分
	 */
	@Override
	public void changePoint(String jiacn, int add) throws Exception{
		User user = userMapper.selectByJiacn(jiacn);
		if(user == null) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
		User upUser = new User();
		upUser.setId(user.getId());
		upUser.setPoint(user.getPoint() + add);
		if(upUser.getPoint() < 0) {
			throw new EsRuntimeException(ErrorConstants.POINT_NO_ENOUGH);
		}
		userMapper.updateByPrimaryKeySelective(upUser);
	}

	@Override
	public void changeRole(User user) {
		//查询用户当前角色
		List<RoleRel> roleRelList = roleRelMapper.selectByUserId(user.getId(), EsSecurityHandler.clientId());
		List<RoleRel> addList = new ArrayList<RoleRel>(); //需要添加的角色
		List<RoleRel> cancelList = new ArrayList<RoleRel>(); //需要取消的角色
		//查找需要添加的角色
		user.getRoleIds().stream().filter(roleId -> {
			return roleRelList.stream().filter(roleRel -> roleId.equals(roleRel.getRoleId())).count() == 0;
		}).forEach(roleId -> {
			RoleRel rel = new RoleRel();
			rel.setUserId(user.getId());
			rel.setRoleId(roleId);
			Long now = DateUtil.genTime(new Date());
			rel.setCreateTime(now);
			rel.setUpdateTime(now);
			addList.add(rel);
		});
		if(addList.size() > 0) {
			roleRelMapper.batchAdd(addList);
		}
		
		//查找需要取消的角色
		roleRelList.stream().filter(roleRel -> !user.getRoleIds().contains(roleRel.getRoleId())).forEach(roleRel -> {
			cancelList.add(roleRel);
		});
		if(cancelList.size() > 0) {
			roleRelMapper.batchDel(cancelList);
		}
	}

	@Override
	public void changeGroup(User user) {
		//查询用户当前角色
		List<GroupRel> groupRelList = groupRelMapper.selectByUserId(user.getId());
		List<GroupRel> addList = new ArrayList<>(); //需要添加的角色
		List<GroupRel> cancelList = new ArrayList<>(); //需要取消的角色
		//查找需要添加的角色
		user.getRoleIds().stream().filter(roleId -> {
			return groupRelList.stream().filter(groupRel -> roleId.equals(groupRel.getGroupId())).count() == 0;
		}).forEach(groupId -> {
			GroupRel rel = new GroupRel();
			rel.setUserId(user.getId());
			rel.setGroupId(groupId);
			Long now = DateUtil.genTime(new Date());
			rel.setCreateTime(now);
			rel.setUpdateTime(now);
			addList.add(rel);
		});
		if(addList.size() > 0) {
			groupRelMapper.batchAdd(addList);
		}

		//查找需要取消的角色
		groupRelList.stream().filter(groupRel -> !user.getGroupIds().contains(groupRel.getGroupId())).forEach(groupRel -> {
			cancelList.add(groupRel);
		});
		if(cancelList.size() > 0) {
			groupRelMapper.batchDel(cancelList);
		}
	}
	
	@Override
	public void changeOrg(User user) {
		//查询用户当前组织
		List<OrgRel> orgRelList = orgRelMapper.selectByUserId(user.getId());
		List<OrgRel> addList = new ArrayList<OrgRel>(); //需要添加的组织
		List<OrgRel> cancelList = new ArrayList<OrgRel>(); //需要取消的组织
		//查找需要添加的组织
		user.getOrgIds().stream().filter(orgId -> {
			return orgRelList.stream().filter(orgRel -> orgId.equals(orgRel.getOrgId())).count() == 0;
		}).forEach(orgId -> {
			OrgRel rel = new OrgRel();
			rel.setUserId(user.getId());
			rel.setOrgId(orgId);
			Long now = DateUtil.genTime(new Date());
			rel.setCreateTime(now);
			rel.setUpdateTime(now);
			addList.add(rel);
		});
		if(addList.size() > 0) {
			orgRelMapper.batchAdd(addList);
		}
		
		//查找需要取消的组织
		orgRelList.stream().filter(orgRel -> !user.getOrgIds().contains(orgRel.getOrgId())).forEach(orgRel -> {
			cancelList.add(orgRel);
		});
		if(cancelList.size() > 0) {
			orgRelMapper.batchDel(cancelList);
		}
	}

	@Override
	public Page<User> listByRoleId(Integer roleId, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return userMapper.selectByRole(roleId);
	}
	
	@Override
	public Page<User> listByGroupId(Integer groupId, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return userMapper.selectByGroup(groupId);
	}

	@Override
	public Page<User> listByOrgId(UserExample example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return userMapper.selectByOrg(example);
	}

	@Override
	public User findByJiacn(String jiacn) {
		return userMapper.selectByJiacn(jiacn);
	}

	@Override
	public User findByOpenid(String openid) {
		return userMapper.selectByOpenid(openid);
	}

	@Override
	public User findByPhone(String phone) {
		return userMapper.selectByPhone(phone);
	}

	@Override
	public Page<User> search(User user, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return userMapper.searchByExample(user);
	}

	@Override
	public void sync(List<User> userList) throws Exception {
		for(User user : userList) {
			//查找LDAP服务器是否有该用户
			Map<String, Object> ldapUser = null;
			Map<String, Object> params = new HashMap<>();
			params.put("telephoneNumber", user.getPhone());
			params.put("email", user.getEmail());
			params.put("openid", user.getOpenid());
			JSONResult<List<Map<String, Object>>> ldapUserResult = ldapService.userFind(params);
			if(ErrorConstants.SUCCESS.equals(ldapUserResult.getCode())) {
				ldapUser = ldapUserResult.getData().get(0);
				user.setJiacn(String.valueOf(ldapUser.get("cn")));
			}
			//将用户添加到ldap服务器
			if(ldapUser == null) {
				params = new HashMap<>();
				String cn = StringUtils.isEmpty(user.getPhone()) ? (StringUtils.isEmpty(user.getEmail()) ? user.getOpenid() : user.getEmail()) : user.getPhone();
				params.put("cn", cn);
				params.put("sn", cn);
				params.put("telephoneNumber", StringUtils.isEmpty(user.getPhone()) ? null : user.getPhone());
				params.put("email", StringUtils.isEmpty(user.getEmail()) ? null : user.getEmail());
				params.put("openid", StringUtils.isEmpty(user.getOpenid()) ? null : user.getOpenid());
				params.put("country", StringUtils.isEmpty(user.getCountry()) ? null : user.getCountry());
				params.put("province", StringUtils.isEmpty(user.getProvince()) ? null : user.getProvince());
				params.put("city", StringUtils.isEmpty(user.getCity()) ? null : user.getCity());
				params.put("sex", user.getSex());
				params.put("nickname", StringUtils.isEmpty(user.getNickname()) ? null : user.getNickname());
				JSONResult<String> newUserResult = ldapService.userCreate(params);
				if(!ErrorConstants.SUCCESS.equals(newUserResult.getCode())) {
					throw new EsRuntimeException(ErrorConstants.DEFAULT_ERROR_CODE);
				}
				user.setJiacn(cn);
			}
			Long now = DateUtil.genTime(new Date());
			//查找本地数据库是否有该用户，如果没有则新增，如果有则更新
			User searchUser = new User();
			searchUser.setJiacn(user.getJiacn());
			searchUser.setPhone(user.getPhone());
			searchUser.setEmail(user.getEmail());
			searchUser.setOpenid(user.getOpenid());
			Page<User> curUser = this.search(searchUser, 1, 1);
			if(curUser == null || curUser.getResult().size() == 0) {
				user.setCreateTime(now);
				user.setUpdateTime(now);
				//新增用户
				userMapper.insertSelective(user);
				//设置默认角色
				RoleRel rel = new RoleRel();
				rel.setRoleId(Constants.DEFAULT_ROLE_ID);
				rel.setUserId(user.getId());
				rel.setCreateTime(now);
				rel.setUpdateTime(now);
				roleRelMapper.insertSelective(rel);
			}else {
				user.setId(curUser.getResult().get(0).getId());
				user.setUpdateTime(now);
				userMapper.updateByPrimaryKeySelective(user);
			}
		}
	}

	@Override
	public List<Integer> findRoleIds(Integer userId) {
		List<RoleRel> roleRelList = roleRelMapper.selectByUserId(userId, EsSecurityHandler.clientId());
		List<Integer> roleIds = new ArrayList<>();
		for(RoleRel rel : roleRelList) {
			roleIds.add(rel.getRoleId());
		}
		return roleIds;
	}

	@Override
	public List<Integer> findOrgIds(Integer userId) {
		List<OrgRel> orgRelList = orgRelMapper.selectByUserId(userId);
		List<Integer> orgIds = new ArrayList<>();
		for(OrgRel rel : orgRelList) {
			orgIds.add(rel.getOrgId());
		}
		return orgIds;
	}

	@Override
	public List<Integer> findGroupIds(Integer userId) {
		List<GroupRel> groupRelList = groupRelMapper.selectByUserId(userId);
		List<Integer> groupIds = new ArrayList<>();
		for(GroupRel rel : groupRelList) {
			groupIds.add(rel.getGroupId());
		}
		return groupIds;
	}

	@Override
	public User findByUsername(String username) {
		return userMapper.selectByUsername(username);
	}

	@Override
	public void changePassword(Integer userId, String oldPassword, String newPassword) throws Exception {
		User user = userMapper.selectByPrimaryKey(userId);
		if(user == null) {
			throw new EsRuntimeException(ErrorConstants.USER_NOT_EXIST);
		}
		if(!user.getPassword().equals(oldPassword)) {
			throw new EsRuntimeException(ErrorConstants.OLD_PASSWORD_WRONG);
		}
		User upUser = new User();
		upUser.setId(userId);
		upUser.setPassword(newPassword);
		userMapper.updateByPrimaryKeySelective(upUser);
	}

	@Override
	public void setDefaultOrg(Integer userId) {
		User user = userMapper.selectByPrimaryKey(userId);
		List<OrgRel> orgRelList = orgRelMapper.selectByUserId(userId);
		if(orgRelList.size() > 0) {
			User upUser = new User();
			upUser.setId(user.getId());
			//如果用户还没有设置默认职位，则默认第一个职位
			if(user.getPosition() == null) {
				upUser.setPosition(orgRelList.get(0).getOrgId());
				userMapper.updateByPrimaryKeySelective(upUser);
			} else { //如果用户当前职位已失效，则默认第一个职位
				boolean aval = false;
				for(OrgRel rel : orgRelList) {
					if(user.getPosition().equals(rel.getOrgId())) {
						aval = true;
						break;
					}
				}
				if(!aval) {
					upUser.setPosition(orgRelList.get(0).getOrgId());
					userMapper.updateByPrimaryKeySelective(upUser);
				}
			}
		}
	}
}
