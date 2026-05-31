package cn.jia.isp.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.isp.entity.CmsTableEntity;

public interface CmsTableDao extends IBaseDao<CmsTableEntity> {

    CmsTableEntity findByName(String name);
    
}