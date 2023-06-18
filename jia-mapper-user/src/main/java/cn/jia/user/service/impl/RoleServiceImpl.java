package cn.jia.user.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.user.entity.Role;
import cn.jia.user.mapper.RoleMapper;
import cn.jia.user.service.IRoleService;
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
public class RoleServiceImpl extends BaseServiceImpl<RoleMapper, Role> implements IRoleService {

}
