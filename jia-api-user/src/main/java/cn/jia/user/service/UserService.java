package cn.jia.user.service;

import cn.jia.user.entity.User;
import cn.jia.user.entity.UserExample;
import com.github.pagehelper.Page;

import java.util.List;

public interface UserService {
	
	public User create(User user) throws Exception;

	public User find(Integer id);
	
	public User findByJiacn(String jiacn);
	
	public User findByUsername(String username);
	
	public User findByOpenid(String openid);
	
	public User findByPhone(String phone);
	
	public Page<User> search(User user, int pageNo, int pageSize);

	public User update(User user);
	
	public void sync(List<User> userList) throws Exception;

	public void delete(Integer id);
	
	public Page<User> list(int pageNo, int pageSize);
	
	public void changePoint(String jiacn, int add) throws Exception;
	
	public void changeRole(User user);

	public void changeGroup(User user);
	
	public void changeOrg(User user);
	
	public Page<User> listByRoleId(Integer roleId, int pageNo, int pageSize);
	
	public Page<User> listByGroupId(Integer groupId, int pageNo, int pageSize);
	
	public Page<User> listByOrgId(UserExample example, int pageNo, int pageSize);
	
	public List<Integer> findRoleIds(Integer userId);
	
	public List<Integer> findOrgIds(Integer userId);

	public List<Integer> findGroupIds(Integer userId);
	
	public void changePassword(Integer userId, String oldPassword, String newPassword) throws Exception;
	
	/**
	 * 更新用户默认职位
	 * @param userId
	 */
	public void setDefaultOrg(Integer userId);
	
}
