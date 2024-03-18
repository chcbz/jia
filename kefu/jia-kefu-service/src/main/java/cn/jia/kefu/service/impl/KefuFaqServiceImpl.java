package cn.jia.kefu.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.kefu.dao.KefuFaqDao;
import cn.jia.kefu.entity.KefuFaqEntity;
import cn.jia.kefu.service.KefuFaqService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KefuFaqServiceImpl extends BaseServiceImpl<KefuFaqDao, KefuFaqEntity> implements KefuFaqService {
}
