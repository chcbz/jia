package cn.jia.material.service.impl;

import cn.jia.core.util.DateUtil;
import cn.jia.material.dao.NewsMapper;
import cn.jia.material.entity.News;
import cn.jia.material.service.NewsService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class NewsServiceImpl implements NewsService {
	
	@Autowired
	private NewsMapper newsMapper;
	
	@Override
	public News create(News news) {
		Long now = DateUtil.genTime(new Date());
		news.setCreateTime(now);
		news.setUpdateTime(now);
		newsMapper.insertSelective(news);
		return news;
	}

	@Override
	public News find(Integer id) {
		return newsMapper.selectByPrimaryKey(id);
	}

	@Override
	public News update(News news) {
		Long now = DateUtil.genTime(new Date());
		news.setUpdateTime(now);
		newsMapper.updateByPrimaryKeySelective(news);
		return news;
	}

	@Override
	public void delete(Integer id) {
		newsMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<News> list(int pageNo, int pageSize, News example) {
		PageHelper.startPage(pageNo, pageSize);
		return newsMapper.selectByExample(example);
	}
}
