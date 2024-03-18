package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.IspFileDao;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.mapper.IspFileMapper;
import jakarta.inject.Named;

@Named
public class IspFileDaoImpl extends BaseDaoImpl<IspFileMapper, IspFileEntity> implements IspFileDao {
    @Override
    public IspFileEntity selectByUri(String uri) {
        return baseMapper.selectByUri(uri);
    }
}