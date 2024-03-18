package cn.jia.isp.service;

import cn.jia.isp.entity.IspFileEntity;
import com.github.pagehelper.PageInfo;

public interface FileService {
	
	IspFileEntity create(IspFileEntity record);

	IspFileEntity find(Long id);

	PageInfo<IspFileEntity> list(IspFileEntity example, int pageNo, int pageSize);

	IspFileEntity update(IspFileEntity record);
	
	void delete(Long id);

	IspFileEntity findByUri(String uri);
}
