package cn.jia.user.service;

import cn.jia.common.service.IBaseService;
import cn.jia.user.entity.PermsEntity;

import java.util.List;

public interface PermsService extends IBaseService<PermsEntity> {
	List<PermsEntity> findByUserId(Long userId);

	List<PermsEntity> findByRoleId(Long roleId);

	void refresh(List<PermsEntity> resourceList);
}
