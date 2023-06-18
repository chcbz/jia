package cn.jia.user.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.user.entity.Org;
import cn.jia.user.mapper.OrgMapper;
import cn.jia.user.service.IOrgService;
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
public class OrgServiceImpl extends BaseServiceImpl<OrgMapper, Org> implements IOrgService {

}
