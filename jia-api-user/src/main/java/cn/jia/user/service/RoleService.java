package cn.jia.user.service;

import cn.jia.core.entity.Action;
import cn.jia.user.entity.Role;
import com.github.pagehelper.Page;

import java.util.List;

public interface RoleService {
	
	public Role create(Role role);

	public Role find(Integer id);

	public Role update(Role role);

	public void delete(Integer id);
	
	public Page<Role> list(int pageNo, int pageSize);
	
	public void changePerms(Role role);
	
	public List<Role> findByUserId(Integer userId, String clientId);
	
	public void batchAddUser(Role role);
	
	public void batchDelUser(Role role);
	
	public List<Action> listPerms(Integer roleId);
}
