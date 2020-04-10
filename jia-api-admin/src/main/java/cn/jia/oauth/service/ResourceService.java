package cn.jia.oauth.service;

import cn.jia.oauth.entity.Resource;
import com.github.pagehelper.Page;

public interface ResourceService {
	
	Resource create(Resource resource);

	Resource find(String resourceId) throws Exception;

	Resource update(Resource resource);

	void delete(String resourceId);
	
	Page<Resource> list(Resource example, int pageNo, int pageSize);
}
