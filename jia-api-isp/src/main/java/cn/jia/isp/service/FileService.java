package cn.jia.isp.service;

import cn.jia.isp.entity.IspFile;
import com.github.pagehelper.Page;

public interface FileService {
	
	IspFile create(IspFile record);

	IspFile find(Integer id);

	Page<IspFile> list(IspFile example, int pageNo, int pageSize);

	IspFile update(IspFile record);
	
	void delete(Integer id);

	IspFile findByUri(String uri);
}
