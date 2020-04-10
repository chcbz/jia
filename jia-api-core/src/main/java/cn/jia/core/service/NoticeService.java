package cn.jia.core.service;

import cn.jia.core.entity.Notice;
import com.github.pagehelper.Page;

public interface NoticeService {
	
	Notice create(Notice record);

	Notice find(Integer id);

	Notice update(Notice record);

	void delete(Integer id);
	
	Page<Notice> findByExample(Notice example, int pageNo, int pageSize);
	
}
