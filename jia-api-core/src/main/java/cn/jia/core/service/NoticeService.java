package cn.jia.core.service;

import cn.jia.core.entity.Notice;
import cn.jia.core.util.DateUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class NoticeService {
	
	@Autowired
	private INoticeService noticeService;

	public Notice create(Notice record) {
		Long now = DateUtil.genTime(new Date());
		record.setCreateTime(now);
		record.setUpdateTime(now);
		noticeService.save(record);
		return record;
	}

	public Notice find(Integer id) {
		return noticeService.getById(id);
	}

	public Notice update(Notice notice) {
		Long now = DateUtil.genTime(new Date());
		notice.setUpdateTime(now);
		noticeService.updateById(notice);
		return notice;
	}

	public void delete(Integer id) {
		noticeService.removeById(id);
	}
	
	public PageInfo<Notice> findByExample(Notice example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		List<Notice> list = noticeService.listByEntity(example);
		return PageInfo.of(list);
	}
}
