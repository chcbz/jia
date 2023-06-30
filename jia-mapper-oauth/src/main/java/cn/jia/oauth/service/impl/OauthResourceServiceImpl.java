package cn.jia.oauth.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.oauth.entity.OauthResourceEntity;
import cn.jia.oauth.mapper.OauthResourceMapper;
import cn.jia.oauth.service.IOauthResourceService;
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
public class OauthResourceServiceImpl extends BaseServiceImpl<OauthResourceMapper, OauthResourceEntity> implements IOauthResourceService {

}
