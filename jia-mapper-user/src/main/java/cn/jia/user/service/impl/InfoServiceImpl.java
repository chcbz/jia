package cn.jia.user.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.mapper.InfoMapper;
import cn.jia.user.service.IInfoService;
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
public class InfoServiceImpl extends BaseServiceImpl<InfoMapper, UserEntity> implements IInfoService {

}
