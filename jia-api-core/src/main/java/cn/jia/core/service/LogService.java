package cn.jia.core.service;

import cn.jia.core.entity.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class LogService {
	
	@Autowired
	private ILogService logService;

	public Log selectById(Integer id) {
		return logService.getById(id);
	}

	@Async
	public void insert(Log log) {
		logService.save(log);
	}

	public void update(Log log) {
		logService.updateById(log);
	}

	public void delete(Integer id) {
		logService.removeById(id);
	}
}
