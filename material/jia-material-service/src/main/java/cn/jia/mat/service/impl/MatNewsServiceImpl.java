package cn.jia.mat.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.mat.dao.MatNewsDao;
import cn.jia.mat.entity.MatNewsEntity;
import cn.jia.mat.service.MatNewsService;
import org.springframework.stereotype.Service;

@Service
public class MatNewsServiceImpl extends BaseServiceImpl<MatNewsDao, MatNewsEntity> implements MatNewsService {
}
