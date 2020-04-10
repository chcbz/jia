package cn.jia.user.service;

import cn.jia.user.entity.Group;
import com.github.pagehelper.Page;

import java.util.List;

public interface GroupService {
	
	public Group create(Group group);

	public Group find(Integer id);

	public Group update(Group group);

	public void delete(Integer id);
	
	public Page<Group> list(Group example, int pageNo, int pageSize);
	
	public List<Group> findByUserId(Integer userId, String clientId);
	
	public void batchAddUser(Group group);
	
	public void batchDelUser(Group group);

	public void changeRole(Group group);

	public List<Integer> findRoleIds(Integer groupId, String clientId);
	
}
