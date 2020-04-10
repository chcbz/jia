package cn.jia.material.service.impl;

import cn.jia.core.util.DateUtil;
import cn.jia.material.dao.MediaMapper;
import cn.jia.material.entity.Media;
import cn.jia.material.service.MediaService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class MediaServiceImpl implements MediaService {
	
	@Autowired
	private MediaMapper mediaMapper;
	
	@Override
	public Media create(Media media) {
		Long now = DateUtil.genTime(new Date());
		media.setTime(now);
		mediaMapper.insertSelective(media);
		return media;
	}

	@Override
	public Media find(Integer id) {
		return mediaMapper.selectByPrimaryKey(id);
	}

	@Override
	public Media update(Media media) {
		Long now = DateUtil.genTime(new Date());
		media.setTime(now);
		mediaMapper.updateByPrimaryKeySelective(media);
		return media;
	}

	@Override
	public void delete(Integer id) {
		mediaMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<Media> list(int pageNo, int pageSize, Media example) {
		PageHelper.startPage(pageNo, pageSize);
		return mediaMapper.selectByExample(example);
	}
}
