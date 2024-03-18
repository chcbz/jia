package cn.jia.base.service.impl;

import cn.jia.base.dao.NoticeDao;
import cn.jia.base.entity.NoticeEntity;
import cn.jia.base.service.NoticeService;
import cn.jia.core.service.BaseServiceImpl;
import jakarta.inject.Named;

@Named
public class NoticeServiceImpl extends BaseServiceImpl<NoticeDao, NoticeEntity> implements NoticeService {
}
