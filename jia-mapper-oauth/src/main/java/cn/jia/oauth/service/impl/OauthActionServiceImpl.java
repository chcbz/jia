package cn.jia.oauth.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.oauth.entity.OauthActionEntity;
import cn.jia.oauth.mapper.OauthActionMapper;
import cn.jia.oauth.service.IOauthActionService;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
@Named
public class OauthActionServiceImpl extends BaseServiceImpl<OauthActionMapper, OauthActionEntity> implements IOauthActionService {

}
