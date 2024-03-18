package cn.jia.user.service;

import cn.jia.common.service.IBaseService;
import cn.jia.user.entity.AuthEntity;
import cn.jia.user.entity.RoleEntity;
import cn.jia.user.entity.RoleVO;
import com.github.pagehelper.PageInfo;

public interface RoleService extends IBaseService<RoleEntity> {
	void changePerms(RoleVO role);
	
	PageInfo<RoleEntity> listByUserId(Long userId, int pageSize, int pageNum);

	PageInfo<RoleEntity> listByGroupId(Long groupId, int pageSize, int pageNum);
	
	void batchAddUser(RoleVO role);
	
	void batchDelUser(RoleVO role);
	
	PageInfo<AuthEntity> listPerms(Long roleId, int pageSize, int pageNum);
}
