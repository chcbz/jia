package cn.jia.user.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.user.entity.Group;
import cn.jia.user.mapper.GroupMapper;
import cn.jia.user.service.IGroupService;
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
public class GroupServiceImpl extends BaseServiceImpl<GroupMapper, Group> implements IGroupService {

}
