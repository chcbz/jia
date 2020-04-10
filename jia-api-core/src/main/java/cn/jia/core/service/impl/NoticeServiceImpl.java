package cn.jia.core.service.impl;

import cn.jia.core.dao.NoticeMapper;
import cn.jia.core.entity.Notice;
import cn.jia.core.service.NoticeService;
import cn.jia.core.util.DateUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class NoticeServiceImpl implements NoticeService {
	
	@Autowired
	private NoticeMapper noticeMapper;

	@Override
	public Notice create(Notice record) {
		Long now = DateUtil.genTime(new Date());
		record.setCreateTime(now);
		record.setUpdateTime(now);
		noticeMapper.insertSelective(record);
		return record;
	}

	@Override
	public Notice find(Integer id) {
		return noticeMapper.selectByPrimaryKey(id);
	}

	@Override
	public Notice update(Notice notice) {
		Long now = DateUtil.genTime(new Date());
		notice.setUpdateTime(now);
		noticeMapper.updateByPrimaryKeySelective(notice);
		return notice;
	}

	@Override
	public void delete(Integer id) {
		noticeMapper.deleteByPrimaryKey(id);
	}
	
	@Override
	public Page<Notice> findByExample(Notice example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return noticeMapper.findByExamplePage(example);
	}

}
