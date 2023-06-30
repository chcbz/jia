package cn.jia.oauth.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.mapper.OauthClientMapper;
import cn.jia.oauth.service.IOauthClientService;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
@Named
public class OauthClientServiceImpl extends BaseServiceImpl<OauthClientMapper, OauthClientEntity> implements IOauthClientService {

}
