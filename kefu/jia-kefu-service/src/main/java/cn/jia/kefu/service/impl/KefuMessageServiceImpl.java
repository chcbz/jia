package cn.jia.kefu.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.kefu.dao.KefuMessageDao;
import cn.jia.kefu.entity.KefuMessageEntity;
import cn.jia.kefu.service.KefuMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KefuMessageServiceImpl extends BaseServiceImpl<KefuMessageDao, KefuMessageEntity> implements KefuMessageService {
}
