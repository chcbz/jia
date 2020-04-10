package cn.jia.material.service;

import cn.jia.material.entity.Media;
import com.github.pagehelper.Page;

public interface MediaService {
	
	Media create(Media media);

	Media find(Integer id);

	Media update(Media media);

	void delete(Integer id);
	
	Page<Media> list(int pageNo, int pageSize, Media example);
}
