package cn.jia.kefu.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.kefu.dao.KefuMsgTypeDao;
import cn.jia.kefu.entity.KefuMsgTypeEntity;
import cn.jia.kefu.service.KefuMsgTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KefuMsgTypeServiceImpl extends BaseServiceImpl<KefuMsgTypeDao, KefuMsgTypeEntity> implements KefuMsgTypeService {
}
