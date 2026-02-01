package cn.jia.user.service;

import cn.jia.common.service.IBaseService;
import cn.jia.user.entity.GroupEntity;
import cn.jia.user.entity.GroupVO;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface GroupService extends IBaseService<GroupEntity> {
	PageInfo<GroupEntity> findPage(GroupEntity example, int pageNum, int pageSize, String orderBy);
	
	List<GroupEntity> findByUserId(Long userId);
	
	void batchAddUser(GroupVO group);
	
	void batchDelUser(GroupVO group);

	void changeRole(GroupVO group);

	List<Long> findRoleIds(Long groupId);
	
}
