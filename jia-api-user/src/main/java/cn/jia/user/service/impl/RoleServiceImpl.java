package cn.jia.user.service.impl;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.Action;
import cn.jia.core.util.DateUtil;
import cn.jia.user.dao.AuthMapper;
import cn.jia.user.dao.RoleMapper;
import cn.jia.user.dao.RoleRelMapper;
import cn.jia.user.entity.Auth;
import cn.jia.user.entity.Role;
import cn.jia.user.entity.RoleRel;
import cn.jia.user.service.RoleService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class RoleServiceImpl implements RoleService {
	
	@Autowired
	private RoleMapper roleMapper;
	@Autowired
	private AuthMapper authMapper;
	@Autowired
	private RoleRelMapper roleRelMapper;

	@Override
	public Role create(Role role) {
		Long now = DateUtil.genTime(new Date());
		role.setCreateTime(now);
		role.setUpdateTime(now);
		role.setClientId(EsSecurityHandler.clientId());
		roleMapper.insertSelective(role);
		return role;
	}

	@Override
	public Role find(Integer id) {
		return roleMapper.selectByPrimaryKey(id);
	}

	@Override
	public Role update(Role role) {
		Long now = DateUtil.genTime(new Date());
		role.setUpdateTime(now);
		roleMapper.updateByPrimaryKeySelective(role);
		return role;
	}

	@Override
	public void delete(Integer id) {
		roleMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<Role> list(int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return roleMapper.selectAll(EsSecurityHandler.clientId());
	}

	@Override
	public void changePerms(Role role) {
		//查询用户当前权限
		List<Auth> authList = authMapper.selectByRoleId(role.getId());
		List<Auth> addList = new ArrayList<>(); //需要添加的角色权限
		List<Auth> cancelList = new ArrayList<>(); //需要取消的角色权限
		//查找需要添加的角色权限
		role.getPermsIds().stream().filter(permsId -> authList.stream().noneMatch(auth -> permsId.equals(auth.getPermsId()))).forEach(permsId -> {
			Auth auth = new Auth();
			auth.setRoleId(role.getId());
			auth.setPermsId(permsId);
			Long now = DateUtil.genTime(new Date());
			auth.setCreateTime(now);
			auth.setUpdateTime(now);
			addList.add(auth);
		});
		if(addList.size() > 0) {
			authMapper.batchAdd(addList);
		}
		
		//查找需要取消的角色权限
		authList.stream().filter(auth -> !role.getPermsIds().contains(auth.getPermsId())).forEach(cancelList::add);
		if(cancelList.size() > 0) {
			authMapper.batchDel(cancelList);
		}
	}

	@Override
	public List<Role> findByUserId(Integer userId, String clientId) {
		return roleMapper.selectByUserId(userId, clientId);
	}

	@Override
	public void batchAddUser(Role role) {
		//查询用户当前角色
		List<RoleRel> roleRelList = roleRelMapper.selectByRoleId(role.getId(), EsSecurityHandler.clientId());
		List<RoleRel> addList = new ArrayList<>(); //需要添加的角色
		//查找需要添加的角色
		role.getUserIds().stream().filter(userId -> roleRelList.stream().noneMatch(roleRel -> userId.equals(roleRel.getUserId()))).forEach(userId -> {
			RoleRel rel = new RoleRel();
			rel.setUserId(userId);
			rel.setRoleId(role.getId());
			rel.setClientId(EsSecurityHandler.clientId());
			Long now = DateUtil.genTime(new Date());
			rel.setCreateTime(now);
			rel.setUpdateTime(now);
			addList.add(rel);
		});
		role.getGroupIds().stream().filter(groupId -> roleRelList.stream().noneMatch(roleRel -> groupId.equals(roleRel.getGroupId()))).forEach(groupId -> {
			RoleRel rel = new RoleRel();
			rel.setGroupId(groupId);
			rel.setRoleId(role.getId());
			rel.setClientId(EsSecurityHandler.clientId());
			Long now = DateUtil.genTime(new Date());
			rel.setCreateTime(now);
			rel.setUpdateTime(now);
			addList.add(rel);
		});
		if(addList.size() > 0) {
			roleRelMapper.batchAdd(addList);
		}
	}

	@Override
	public void batchDelUser(Role role) {
		//查询用户当前角色
		List<RoleRel> roleRelList = roleRelMapper.selectByRoleId(role.getId(), EsSecurityHandler.clientId());
		List<RoleRel> cancelList = new ArrayList<>(); //需要取消的角色
		
		//查找需要取消的角色
		roleRelList.stream().filter(roleRel -> role.getUserIds().contains(roleRel.getUserId())).forEach(cancelList::add);
		roleRelList.stream().filter(roleRel -> role.getGroupIds().contains(roleRel.getGroupId())).forEach(cancelList::add);
		if(cancelList.size() > 0) {
			roleRelMapper.batchDel(cancelList);
		}
	}

	@Override
	public List<Action> listPerms(Integer roleId) {
		return authMapper.selectPermsByRole(roleId);
	}
}
