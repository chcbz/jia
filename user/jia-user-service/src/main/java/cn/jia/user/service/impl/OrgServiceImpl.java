package cn.jia.user.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.StringUtils;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.dao.UserOrgDao;
import cn.jia.user.dao.UserOrgRelDao;
import cn.jia.user.dao.UserRoleDao;
import cn.jia.user.entity.OrgEntity;
import cn.jia.user.entity.OrgRelEntity;
import cn.jia.user.entity.OrgVO;
import cn.jia.user.entity.RoleEntity;
import cn.jia.user.service.OrgService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class OrgServiceImpl extends BaseServiceImpl<UserOrgDao, OrgEntity> implements OrgService {
	@Autowired
	private UserOrgRelDao userOrgRelDao;
	@Autowired
	private UserRoleDao userRoleDao;

	@Override
	public PageInfo<OrgEntity> list(int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return PageInfo.of(baseDao.selectAll());
	}

	@Override
	public PageInfo<OrgEntity> listSub(Long orgId, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return PageInfo.of(baseDao.selectByParentId(orgId));
	}

	@Override
	public List<OrgEntity> findByUserId(Long userId) {
		return baseDao.selectByUserId(userId);
	}

	@Override
	public void batchAddUser(OrgVO org) {
		//查询用户当前组织
		List<OrgRelEntity> orgRelList = userOrgRelDao.selectByOrgId(org.getId());
		List<OrgRelEntity> addList = new ArrayList<>(); //需要添加的组织
		//查找需要添加的组织
		org.getUserIds().stream().filter(userId -> orgRelList.stream().noneMatch(orgRel ->
				userId.equals(orgRel.getUserId()))).forEach(userId -> {
			OrgRelEntity rel = new OrgRelEntity();
			rel.setUserId(userId);
			rel.setOrgId(org.getId());
			addList.add(rel);
		});
		if(addList.size() > 0) {
			userOrgRelDao.insertBatch(addList);
		}
	}

	@Override
	public void batchDelUser(OrgVO org) {
		//查询用户当前组织
		List<OrgRelEntity> orgRelList = userOrgRelDao.selectByOrgId(org.getId());
		List<OrgRelEntity> cancelList = new ArrayList<>(); //需要取消的组织
		
		//查找需要取消的组织
		orgRelList.stream().filter(orgRel -> org.getUserIds().contains(orgRel.getUserId())).forEach(cancelList::add);
		if(cancelList.size() > 0) {
			userOrgRelDao.deleteBatchIds(cancelList);
		}
	}

	@Override
	public String findDirector(Long curOrgId, String role) {
		RoleEntity r = userRoleDao.selectByCode(role);
		if(r == null) {
			throw new EsRuntimeException(UserErrorConstants.ROLE_NOT_EXIST);
		}
		return findDirector(curOrgId, r.getId(), new HashSet<>());
	}
	
	private String findDirector(Long curOrgId, Long roleId, Set<Long> checkedList) {
		OrgEntity org = null;
		//忽略已经检查过的部门
		if(!checkedList.contains(curOrgId)) {
			//判断当前部门是否存在对应角色，有则直接返回
			String director = baseDao.findDirector(curOrgId, roleId);
			if(StringUtils.isNotEmpty(director)) {
				return director;
			}
			checkedList.add(curOrgId);
			
			//判断是否有子部门，如果有，获取第一个子部门
			org = baseDao.findFirstChild(curOrgId);
		}
		
		//如果没有子部门，则获取同级下一个部门
		if(org == null) {
			//如果是由子组织上升到父组织，则从第一个节点开始遍历
			OrgEntity preOrg = baseDao.findPreOrg(curOrgId);
			if(preOrg == null || checkedList.contains(preOrg.getId())) {
				org = baseDao.findNextOrg(curOrgId);
			} else {
				org = baseDao.findFirstChild(preOrg.getPId());
			}
		}
		//如果没有同级未检查部门，从父组织上寻找
		if(org == null) {
			org = baseDao.findParentOrg(curOrgId);
		}
		
		if(org != null) {
			return findDirector(org.getId(), roleId, checkedList);
		}else {
			return null;
		}
	}

	@Override
	public OrgEntity findParent(Long id) {
		OrgEntity org = baseDao.selectById(id);
		if(org != null && org.getPId() != null) {
			return baseDao.selectById(org.getPId());
		}
		return null;
	}
}
