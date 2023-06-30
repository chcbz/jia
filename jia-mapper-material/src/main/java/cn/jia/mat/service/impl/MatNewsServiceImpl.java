package cn.jia.mat.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.mat.entity.MatNewsEntity;
import cn.jia.mat.mapper.MatNewsMapper;
import cn.jia.mat.service.IMatNewsService;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
@Named
public class MatNewsServiceImpl extends BaseServiceImpl<MatNewsMapper, MatNewsEntity> implements IMatNewsService {

}
