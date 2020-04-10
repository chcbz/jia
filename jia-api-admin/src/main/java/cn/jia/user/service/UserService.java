package cn.jia.user.service;

import cn.jia.core.entity.Action;
import cn.jia.user.entity.User;
import cn.jia.user.entity.UserExample;
import com.github.pagehelper.Page;

import java.util.List;

public interface UserService {
	
	User create(User user) throws Exception;

	User find(Integer id);
	
	User findByJiacn(String jiacn);
	
	User findByUsername(String username);
	
	User findByOpenid(String openid);
	
	User findByPhone(String phone);
	
	Page<User> search(User user, int pageNo, int pageSize);

	User update(User user);
	
	void sync(List<User> userList) throws Exception;

	void delete(Integer id);
	
	Page<User> list(User example, int pageNo, int pageSize);
	
	void changePoint(String jiacn, int add) throws Exception;
	
	void changeRole(User user, String clientId);

	void changeGroup(User user);
	
	void changeOrg(User user);
	
	Page<User> listByRoleId(Integer roleId, int pageNo, int pageSize);
	
	Page<User> listByGroupId(Integer groupId, int pageNo, int pageSize);
	
	Page<User> listByOrgId(UserExample example, int pageNo, int pageSize);
	
	List<Integer> findRoleIds(Integer userId, String clientId);
	
	List<Integer> findOrgIds(Integer userId);

	List<Integer> findGroupIds(Integer userId);
	
	void changePassword(Integer userId, String oldPassword, String newPassword) throws Exception;

	void resetPassword(String phone, String newPassword) throws Exception;
	
	/**
	 * 更新用户默认职位
	 * @param userId
	 */
	void setDefaultOrg(Integer userId);

	Page<Action> listPerms(Integer userId, int pageNo, int pageSize, String clientId);
	
}
