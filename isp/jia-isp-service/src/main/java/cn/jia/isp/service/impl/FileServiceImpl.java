package cn.jia.isp.service.impl;

import cn.jia.core.util.DateUtil;
import cn.jia.isp.dao.IspFileDao;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.service.FileService;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageHelper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class FileServiceImpl implements FileService {
	
	@Inject
	private IspFileDao ispFileDao;

	@Override
	public IspFileEntity create(IspFileEntity file) {
		Long now = DateUtil.nowTime();
		file.setCreateTime(now);
		file.setUpdateTime(now);
		ispFileDao.insert(file);
		return file;
	}

	@Override
	public IspFileEntity find(Long id) {
		return ispFileDao.selectById(id);
	}

	@Override
	public IspFileEntity update(IspFileEntity file) {
		Long now = DateUtil.nowTime();
		file.setUpdateTime(now);
		ispFileDao.updateById(file);
		return file;
	}

	@Override
	public void delete(Long id) {
		ispFileDao.deleteById(id);
	}

	@Override
	public IspFileEntity findByUri(String uri) {
		return ispFileDao.selectByUri(uri);
	}

	@Override
	public PageInfo<IspFileEntity> list(IspFileEntity example, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return PageInfo.of(ispFileDao.selectByEntity(example));
	}

}
