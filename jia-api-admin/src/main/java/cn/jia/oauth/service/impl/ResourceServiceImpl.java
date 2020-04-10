package cn.jia.oauth.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.oauth.dao.ResourceMapper;
import cn.jia.oauth.entity.Resource;
import cn.jia.user.common.ErrorConstants;
import cn.jia.oauth.service.ResourceService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class ResourceServiceImpl implements ResourceService {
	
	@Autowired
	private ResourceMapper resourceMapper;
	
	@Override
	public Resource create(Resource resource) {
		Long now = DateUtil.genTime(new Date());
		resource.setCreateTime(now);
		resource.setUpdateTime(now);
		resourceMapper.insertSelective(resource);
		return resource;
	}

	@Override
	public Resource find(String resourceId) throws Exception {
		Resource resource = resourceMapper.selectByPrimaryKey(resourceId);
		if(resource == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return resource;
	}

	@Override
	public Resource update(Resource resource) {
		Long now = DateUtil.genTime(new Date());
		resource.setUpdateTime(now);
		resourceMapper.updateByPrimaryKeySelective(resource);
		return resource;
	}

	@Override
	public void delete(String resourceId) {
		resourceMapper.deleteByPrimaryKey(resourceId);
	}

	@Override
	public Page<Resource> list(Resource example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return resourceMapper.selectByExamplePage(example);
	}

}
