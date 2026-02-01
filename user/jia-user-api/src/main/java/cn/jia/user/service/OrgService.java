package cn.jia.user.service;

import cn.jia.common.service.IBaseService;
import cn.jia.user.entity.OrgEntity;
import cn.jia.user.entity.OrgVO;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface OrgService extends IBaseService<OrgEntity> {
	OrgEntity findParent(Long id);

	/**
	 * 获取组织架构列表
	 * @param pageNum 页码
	 * @param pageSize 每页数量
	 * @param orderBy 排序字段
	 * @return 组织架构列表
	 */
	PageInfo<OrgEntity> list(int pageNum, int pageSize, String orderBy);

	/**
	 * 获取子组织架构列表
	 * @param parentId 父级ID
	 * @param pageNum 页码
	 * @param pageSize 每页数量
	 * @param orderBy 排序字段
	 * @return 子组织架构列表
	 */
	PageInfo<OrgEntity> listSub(Long parentId, int pageNum, int pageSize, String orderBy);
	
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