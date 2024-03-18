package cn.jia.isp.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.isp.dao.CmsConfigDao;
import cn.jia.isp.entity.CmsConfigEntity;
import cn.jia.isp.mapper.CmsConfigMapper;
import jakarta.inject.Named;

@Named
public class CmsConfigDaoImpl extends BaseDaoImpl<CmsConfigMapper, CmsConfigEntity> implements CmsConfigDao {
}