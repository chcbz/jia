package cn.jia.user.service;

import cn.jia.common.service.IBaseService;
import cn.jia.user.entity.OrgEntity;
import cn.jia.user.entity.OrgVO;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface OrgService extends IBaseService<OrgEntity> {
	OrgEntity findParent(Long id);

	PageInfo<OrgEntity> list(int pageNo, int pageSize);
	
	PageInfo<OrgEntity> listSub(Long orgId, int pageNo, int pageSize);
	
	List<OrgEntity> findByUserId(Long userId);

	void batchAddUser(OrgVO org);
	
	void batchDelUser(OrgVO org);

	/**
	 * 根据当前组织和对应角色，获取审核人
	 *
	 * @param curOrgId 当前组织ID
	 * @param role 角色
	 * @return 审核人
	 * @throws Exception 异常
	 */
	String findDirector(Long curOrgId, String role) throws Exception;
}