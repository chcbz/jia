package cn.jia.user.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.user.entity.AuthEntity;
import cn.jia.user.mapper.AuthMapper;
import cn.jia.user.service.IAuthService;
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
public class AuthServiceImpl extends BaseServiceImpl<AuthMapper, AuthEntity> implements IAuthService {

}
