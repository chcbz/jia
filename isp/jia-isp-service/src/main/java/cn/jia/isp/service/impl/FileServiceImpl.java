package cn.jia.isp.service.impl;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.FileUtil;
import cn.jia.core.util.ImgUtil;
import cn.jia.isp.dao.IspFileDao;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.enums.IspFileTypeEnum;
import cn.jia.isp.service.FileService;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageHelper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.beans.factory.annotation.Value;

@Named
public class FileServiceImpl implements FileService {
	@Inject
	private IspFileDao ispFileDao;

	@Value("${jia.file.path:}")
	private String filePath;

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

	/**
	 * 根据URL创建文件
	 * @param url 头像地址
	 * @param fileType 文件类型
	 */
	@Override
	public IspFileEntity create(String url, IspFileTypeEnum fileType, String fileName) {
		String actualFileName = DateUtil.getDateString() + "_" + fileName;
		byte[] b = ImgUtil.fromUrl(url);
		if (b == null) {
			return null;
		}
		FileUtil.create(b, filePath + "/" + fileType.getName() + "/" + actualFileName);

		//保存文件信息
		IspFileEntity cf = new IspFileEntity();
		cf.init4Creation();
		cf.setClientId(EsContextHolder.getContext().getClientId());
		cf.setExtension(FileUtil.getExtension(actualFileName));
		cf.setName(fileName);
		cf.setSize((long) b.length);
		cf.setType(fileType.getCode());
		cf.setUri(fileType.getName() + "/" + actualFileName);
		ispFileDao.insert(cf);

		return cf;
	}

}
