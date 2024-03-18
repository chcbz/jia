package cn.jia.mat.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.mat.dao.MatPvLogDao;
import cn.jia.mat.entity.MatPvLogEntity;
import cn.jia.mat.service.MatPvLogService;
import org.springframework.stereotype.Service;

@Service
public class MatPvLogServiceImpl extends BaseServiceImpl<MatPvLogDao, MatPvLogEntity> implements MatPvLogService {
}
