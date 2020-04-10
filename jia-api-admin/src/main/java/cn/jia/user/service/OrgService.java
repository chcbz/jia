package cn.jia.user.service;

import cn.jia.user.entity.Org;
import com.github.pagehelper.Page;

import java.util.List;

public interface OrgService {
	
	Org create(Org org);

	Org find(Integer id);

	Org findByCode(String code);
	
	Org findParent(Integer id);

	Org update(Org org);

	void delete(Integer id);
	
	Page<Org> list(int pageNo, int pageSize);
	
	Page<Org> listSub(Integer orgId, int pageNo, int pageSize);
	
	List<Org> findByUserId(Integer userId);

	void batchAddUser(Org org);
	
	void batchDelUser(Org org);

	/**
	 * 根据当前组织和对应角色，获取审核人
	 * @param curOrgId
	 * @param role
	 * @return
	 * @throws Exception
	 */
	String findDirector(Integer curOrgId, String role) throws Exception;
}