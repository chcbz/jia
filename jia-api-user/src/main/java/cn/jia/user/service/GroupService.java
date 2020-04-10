package cn.jia.user.service;

import java.util.List;

import com.github.pagehelper.Page;

import cn.jia.user.entity.Group;

public interface GroupService {
	
	public Group create(Group group);

	public Group find(Integer id);

	public Group update(Group group);

	public void delete(Integer id);
	
	public Page<Group> list(int pageNo, int pageSize);
	
	public List<Group> findByUserId(Integer userId);
	
	public void batchAddUser(Group group);
	
	public void batchDelUser(Group group);

	public void changeRole(Group group);
	
}
