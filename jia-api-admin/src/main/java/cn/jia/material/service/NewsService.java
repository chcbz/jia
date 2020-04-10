package cn.jia.material.service;

import cn.jia.material.entity.News;
import com.github.pagehelper.Page;

public interface NewsService {
	
	News create(News news);

	News find(Integer id);

	News update(News news);

	void delete(Integer id);
	
	Page<News> list(int pageNo, int pageSize, News example);
}
