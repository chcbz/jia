package cn.jia.user.service;

import cn.jia.common.service.IBaseService;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.entity.UserVO;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface UserService extends IBaseService<UserEntity> {

	UserEntity findByJiacn(String jiacn);
	
	UserEntity findByUsername(String username);
	
	UserEntity findByOpenid(String openid);
	
	UserEntity findByPhone(String phone);

	List<UserEntity> search(UserEntity user);
	
	PageInfo<UserEntity> search(UserEntity user, int pageNum, int pageSize, String orderBy);

	UserEntity update(UserEntity user);
	
	void sync(List<UserEntity> userList);

	void changePoint(String jiacn, int add);
	
	void changeRole(UserVO user);

	void changeGroup(UserVO user);
	
	void changeOrg(UserVO user);
	
	PageInfo<UserEntity> listByRoleId(Long roleId, int pageNum, int pageSize, String orderBy);
	
	PageInfo<UserEntity> listByGroupId(Long groupId, int pageNum, int pageSize, String orderBy);
	
	PageInfo<UserEntity> listByOrgId(Long orgId, int pageNum, int pageSize, String orderBy);
	
	List<Long> findRoleIds(Long userId);
	
	List<Long> findOrgIds(Long userId);

	List<Long> findGroupIds(Long userId);

	void changePassword(Long userId, String oldPassword, String newPassword);

	void resetPassword(String phone, String newPassword);
	
	/**
	 * 更新用户默认职位
	 * @param userId 用户ID
	 */
	void setDefaultOrg(Long userId);

}
