package cn.jia.core.service.impl;

import cn.jia.core.dao.LogMapper;
import cn.jia.core.entity.Log;
import cn.jia.core.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class LogServiceImpl implements LogService {
	
	@Autowired
	private LogMapper logMapper;

	@Override
	public Log selectById(Integer id) {
		return logMapper.selectByPrimaryKey(id);
	}

	@Async
	@Override
	public void insert(Log log) {
		logMapper.insertSelective(log);
	}

	@Override
	public void update(Log log) {
		logMapper.updateByPrimaryKeySelective(log);
	}

	@Override
	public void delete(Integer id) {
		logMapper.deleteByPrimaryKey(id);
	}
}
