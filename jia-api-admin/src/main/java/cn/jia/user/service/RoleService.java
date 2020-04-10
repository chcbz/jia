package cn.jia.user.service;

import cn.jia.core.entity.Action;
import cn.jia.user.entity.Role;
import com.github.pagehelper.Page;

public interface RoleService {
	
	Role create(Role role);

	Role find(Integer id);

	Role update(Role role);

	void delete(Integer id);
	
	Page<Role> list(Role example, int pageNo, int pageSize);
	
	void changePerms(Role role);
	
	Page<Role> listByUserId(Integer userId, String clientId, int pageNo, int pageSize);

	Page<Role> listByGroupId(Integer groupId, String clientId, int pageNo, int pageSize);
	
	void batchAddUser(Role role);
	
	void batchDelUser(Role role);
	
	Page<Action> listPerms(Integer roleId, int pageNo, int pageSize);
}
