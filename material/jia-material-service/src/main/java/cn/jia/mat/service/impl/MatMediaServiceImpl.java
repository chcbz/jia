package cn.jia.mat.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.mat.dao.MatMediaDao;
import cn.jia.mat.entity.MatMediaEntity;
import cn.jia.mat.service.MatMediaService;
import org.springframework.stereotype.Service;

@Service
public class MatMediaServiceImpl extends BaseServiceImpl<MatMediaDao, MatMediaEntity> implements MatMediaService {
}
