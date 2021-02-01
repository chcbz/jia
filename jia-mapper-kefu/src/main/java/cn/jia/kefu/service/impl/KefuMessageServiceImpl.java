package cn.jia.kefu.service.impl;

import cn.jia.kefu.entity.KefuMessage;
import cn.jia.kefu.mapper.KefuMessageMapper;
import cn.jia.kefu.service.IKefuMessageService;
import cn.jia.common.service.impl.BaseServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-01-29
 */
@Service
public class KefuMessageServiceImpl extends BaseServiceImpl<KefuMessageMapper, KefuMessage> implements IKefuMessageService {

}
