package cn.jia.user.service;

import cn.jia.common.service.IBaseService;
import cn.jia.user.entity.PermsRelEntity;
import cn.jia.user.entity.RoleEntity;
import cn.jia.user.entity.RoleVO;
import com.github.pagehelper.PageInfo;

public interface RoleService extends IBaseService<RoleEntity> {
	void changePerms(RoleVO role);
	
	/**
	 * 根据用户ID获取角色列表
	 * @param userId 用户ID
	 * @param pageSize 每页大小
	 * @param pageNum 页码
	 * @param orderBy 排序字段
	 * @return 角色分页列表
	 */
	PageInfo<RoleEntity> listByUserId(Long userId, int pageNum, int pageSize, String orderBy);

	/**
	 * 根据组ID获取角色列表
	 * @param groupId 组ID
	 * @param pageSize 每页大小
	 * @param pageNum 页码
	 * @param orderBy 排序字段
	 * @return 角色分页列表
	 */
	PageInfo<RoleEntity> listByGroupId(Long groupId, int pageNum, int pageSize, String orderBy);
	
	void batchAddUser(RoleVO role);
	
	void batchDelUser(RoleVO role);
	
	/**
	 * 获取角色权限列表
	 * @param roleId 角色ID
	 * @param pageSize 每页大小
	 * @param pageNum 页码
	 * @param orderBy 排序字段
	 * @return 权限关系分页列表
	 */
	PageInfo<PermsRelEntity> listPerms(Long roleId, int pageNum, int pageSize, String orderBy);
}
