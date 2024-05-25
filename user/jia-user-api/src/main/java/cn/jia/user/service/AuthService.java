package cn.jia.user.service;

import cn.jia.common.service.IBaseService;
import cn.jia.user.entity.AuthEntity;

import java.util.List;

public interface AuthService extends IBaseService<AuthEntity> {
	List<AuthEntity> findByUserId(Long userId);

	List<AuthEntity> findByRoleId(Long roleId);

}
