package cn.jia.user.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.user.common.ErrorConstants;
import cn.jia.user.dao.OrgMapper;
import cn.jia.user.dao.OrgRelMapper;
import cn.jia.user.dao.RoleMapper;
import cn.jia.user.entity.Org;
import cn.jia.user.entity.OrgRel;
import cn.jia.user.entity.Role;
import cn.jia.user.service.OrgService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class OrgServiceImpl implements OrgService {
	
	@Autowired
	private OrgMapper orgMapper;
	@Autowired
	private OrgRelMapper orgRelMapper;
	@Autowired
	private RoleMapper roleMapper;

	@Override
	public Org create(Org org) {
		Long now = DateUtil.genTime(new Date());
		org.setCreateTime(now);
		org.setUpdateTime(now);
		orgMapper.insertSelective(org);
		return org;
	}

	@Override
	public Org find(Integer id) {
		return orgMapper.selectByPrimaryKey(id);
	}

	@Override
	public Org findByCode(String code) {
		return orgMapper.findByCode(code);
	}

	@Override
	public Org update(Org org) {
		Long now = DateUtil.genTime(new Date());
		org.setUpdateTime(now);
		orgMapper.updateByPrimaryKeySelective(org);
		return org;
	}

	@Override
	public void delete(Integer id) {
		orgMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<Org> list(int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return orgMapper.selectAll();
	}

	@Override
	public Page<Org> listSub(Integer orgId, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return orgMapper.selectByParentId(orgId);
	}

	@Override
	public List<Org> findByUserId(Integer userId) {
		return orgMapper.selectByUserId(userId);
	}

	@Override
	public void batchAddUser(Org org) {
		//查询用户当前组织
		List<OrgRel> orgRelList = orgRelMapper.selectByOrgId(org.getId());
		List<OrgRel> addList = new ArrayList<>(); //需要添加的组织
		//查找需要添加的组织
		org.getUserIds().stream().filter(userId -> orgRelList.stream().noneMatch(orgRel -> userId.equals(orgRel.getUserId()))).forEach(userId -> {
			OrgRel rel = new OrgRel();
			rel.setUserId(userId);
			rel.setOrgId(org.getId());
			Long now = DateUtil.genTime(new Date());
			rel.setCreateTime(now);
			rel.setUpdateTime(now);
			addList.add(rel);
		});
		if(addList.size() > 0) {
			orgRelMapper.batchAdd(addList);
		}
	}

	@Override
	public void batchDelUser(Org org) {
		//查询用户当前组织
		List<OrgRel> orgRelList = orgRelMapper.selectByOrgId(org.getId());
		List<OrgRel> cancelList = new ArrayList<>(); //需要取消的组织
		
		//查找需要取消的组织
		orgRelList.stream().filter(orgRel -> org.getUserIds().contains(orgRel.getUserId())).forEach(cancelList::add);
		if(cancelList.size() > 0) {
			orgRelMapper.batchDel(cancelList);
		}
	}

	@Override
	public String findDirector(Integer curOrgId, String role) throws Exception {
		Role r = roleMapper.selectByCode(role);
		if(r == null) {
			throw new EsRuntimeException(ErrorConstants.ROLE_NOT_EXIST);
		}
		return findDirector(curOrgId, r.getId(), new HashSet<>());
	}
	
	private String findDirector(Integer curOrgId, Integer roleId, Set<Integer> checkedList) {
		Org org = null;
		//忽略已经检查过的部门
		if(!checkedList.contains(curOrgId)) {
			//判断当前部门是否存在对应角色，有则直接返回
			String director = orgMapper.findDirector(curOrgId, roleId);
			if(StringUtils.isNotEmpty(director)) {
				return director;
			}
			checkedList.add(curOrgId);
			
			//判断是否有子部门，如果有，获取第一个子部门
			org = orgMapper.findFirstChild(curOrgId);
		}
		
		//如果没有子部门，则获取同级下一个部门
		if(org == null) {
			//如果是由子组织上升到父组织，则从第一个节点开始遍历
			Org preOrg = orgMapper.findPreOrg(curOrgId);
			if(preOrg == null || checkedList.contains(preOrg.getId())) {
				org = orgMapper.findNextOrg(curOrgId);
			} else {
				org = orgMapper.findFirstChild(preOrg.getpId());
			}
		}
		//如果没有同级未检查部门，从父组织上寻找
		if(org == null) {
			org = orgMapper.findParentOrg(curOrgId);
		}
		
		if(org != null) {
			return findDirector(org.getId(), roleId, checkedList);
		}else {
			return null;
		}
	}

	@Override
	public Org findParent(Integer id) {
		Org org = orgMapper.selectByPrimaryKey(id);
		if(org != null && org.getpId() != null) {
			return orgMapper.selectByPrimaryKey(org.getpId());
		}
		return null;
	}
}
