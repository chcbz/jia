package cn.jia.isp.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.isp.entity.IspFileEntity;

public interface IspFileDao extends IBaseDao<IspFileEntity> {

    IspFileEntity selectByUri(String uri);
}