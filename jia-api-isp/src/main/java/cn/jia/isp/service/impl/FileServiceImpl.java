package cn.jia.isp.service.impl;

import cn.jia.core.util.DateUtil;
import cn.jia.isp.dao.IspFileMapper;
import cn.jia.isp.entity.IspFile;
import cn.jia.isp.service.FileService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class FileServiceImpl implements FileService {
	
	@Autowired
	private IspFileMapper fileMapper;

	@Override
	public IspFile create(IspFile file) {
		Long now = DateUtil.genTime(new Date());
		file.setCreateTime(now);
		file.setUpdateTime(now);
		fileMapper.insertSelective(file);
		return file;
	}

	@Override
	public IspFile find(Integer id) {
		return fileMapper.selectByPrimaryKey(id);
	}

	@Override
	public IspFile update(IspFile file) {
		Long now = DateUtil.genTime(new Date());
		file.setUpdateTime(now);
		fileMapper.updateByPrimaryKeySelective(file);
		return file;
	}

	@Override
	public void delete(Integer id) {
		fileMapper.deleteByPrimaryKey(id);
	}

	@Override
	public IspFile findByUri(String uri) {
		return fileMapper.selectByUri(uri);
	}

	@Override
	public Page<IspFile> list(IspFile example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return fileMapper.selectByExample(example);
	}

}
