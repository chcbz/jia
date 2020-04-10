package cn.jia.user.service;

import java.util.List;

import com.github.pagehelper.Page;

import cn.jia.user.entity.Org;

public interface OrgService {
	
	public Org create(Org org);

	public Org find(Integer id);
	
	public Org findParent(Integer id);

	public Org update(Org org);

	public void delete(Integer id);
	
	public Page<Org> list(int pageNo, int pageSize);
	
	public Page<Org> listSub(Integer orgId, int pageNo, int pageSize);
	
	public List<Org> findByUserId(Integer userId);

	public void batchAddUser(Org org);
	
	public void batchDelUser(Org org);
	
	/**
	 * 根据当前组织和对应角色，获取审核人
	 * @param curOrgId
	 * @param roleId
	 * @return
	 */
	public String findDirector(Integer curOrgId, String role) throws Exception;
}