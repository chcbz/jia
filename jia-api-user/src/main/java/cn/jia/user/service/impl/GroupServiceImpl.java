package cn.jia.user.service.impl;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.util.DateUtil;
import cn.jia.user.dao.GroupMapper;
import cn.jia.user.dao.GroupRelMapper;
import cn.jia.user.dao.RoleRelMapper;
import cn.jia.user.entity.Group;
import cn.jia.user.entity.GroupRel;
import cn.jia.user.entity.RoleRel;
import cn.jia.user.service.GroupService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class GroupServiceImpl implements GroupService {
	
	@Autowired
	private GroupMapper groupMapper;
	@Autowired
	private GroupRelMapper groupRelMapper;
	@Autowired
	private RoleRelMapper roleRelMapper;

	@Override
	public Group create(Group group) {
		Long now = DateUtil.genTime(new Date());
		group.setCreateTime(now);
		group.setUpdateTime(now);
		group.setClientId(EsSecurityHandler.clientId());
		groupMapper.insertSelective(group);
		return group;
	}

	@Override
	public Group find(Integer id) {
		return groupMapper.selectByPrimaryKey(id);
	}

	@Override
	public Group update(Group group) {
		Long now = DateUtil.genTime(new Date());
		group.setUpdateTime(now);
		groupMapper.updateByPrimaryKeySelective(group);
		return group;
	}

	@Override
	public void delete(Integer id) {
		groupMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<Group> list(int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return groupMapper.selectAll(EsSecurityHandler.clientId());
	}

	@Override
	public List<Group> findByUserId(Integer userId) {
		return groupMapper.selectByUserId(userId, EsSecurityHandler.clientId());
	}

	@Override
	public void batchAddUser(Group group) {
		//查询用户当前角色
		List<GroupRel> groupRelList = groupRelMapper.selectByGroupId(group.getId());
		List<GroupRel> addList = new ArrayList<>(); //需要添加的角色
		//查找需要添加的角色
		group.getUserIds().stream().filter(userId -> groupRelList.stream().noneMatch(groupRel -> userId.equals(groupRel.getUserId()))).forEach(userId -> {
			GroupRel rel = new GroupRel();
			rel.setUserId(userId);
			rel.setGroupId(group.getId());
			Long now = DateUtil.genTime(new Date());
			rel.setCreateTime(now);
			rel.setUpdateTime(now);
			addList.add(rel);
		});
		if(addList.size() > 0) {
			groupRelMapper.batchAdd(addList);
		}
	}

	@Override
	public void batchDelUser(Group group) {
		//查询用户当前角色
		List<GroupRel> groupRelList = groupRelMapper.selectByGroupId(group.getId());
		List<GroupRel> cancelList = new ArrayList<>(); //需要取消的角色
		
		//查找需要取消的角色
		groupRelList.stream().filter(groupRel -> group.getUserIds().contains(groupRel.getUserId())).forEach(cancelList::add);
		if(cancelList.size() > 0) {
			groupRelMapper.batchDel(cancelList);
		}
	}

	@Override
	public void changeRole(Group group) {
		//查询用户当前角色
		List<RoleRel> roleRelList = roleRelMapper.selectByGroupId(group.getId(), EsSecurityHandler.clientId());
		List<RoleRel> addList = new ArrayList<>(); //需要添加的角色
		List<RoleRel> cancelList = new ArrayList<>(); //需要取消的角色
		//查找需要添加的角色
		group.getRoleIds().stream().filter(roleId -> roleRelList.stream().noneMatch(roleRel -> roleId.equals(roleRel.getRoleId()))).forEach(roleId -> {
			RoleRel rel = new RoleRel();
			rel.setGroupId(group.getId());
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
		roleRelList.stream().filter(roleRel -> !group.getRoleIds().contains(roleRel.getRoleId())).forEach(cancelList::add);
		if(cancelList.size() > 0) {
			roleRelMapper.batchDel(cancelList);
		}
	}
}
