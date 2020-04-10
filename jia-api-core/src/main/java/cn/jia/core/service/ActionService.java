package cn.jia.core.service;

import cn.jia.core.entity.Action;
import com.github.pagehelper.Page;

import java.util.List;

public interface ActionService {
	
	Action create(Action perms);

	Action find(Integer id);

	Action update(Action perms);
	
	void refresh(List<Action> permsList);

	void delete(Integer id);
	
	Page<Action> list(int pageNo, int pageSize);
	
	Page<Action> findByExample(Action example, int pageNo, int pageSize);
	
}
