package cn.jia.oauth.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.oauth.dao.OauthResourceDao;
import cn.jia.oauth.entity.OauthResourceEntity;
import cn.jia.oauth.service.ResourceService;
import org.springframework.stereotype.Service;

@Service
public class ResourceServiceImpl extends BaseServiceImpl<OauthResourceDao, OauthResourceEntity> implements ResourceService {
}
