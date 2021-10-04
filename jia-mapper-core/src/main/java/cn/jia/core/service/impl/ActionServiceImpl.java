package cn.jia.core.service.impl;

import cn.jia.core.entity.Action;
import cn.jia.core.mapper.ActionMapper;
import cn.jia.core.service.IActionService;
import cn.jia.common.service.impl.BaseServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-10-02
 */
@Service
public class ActionServiceImpl extends BaseServiceImpl<ActionMapper, Action> implements IActionService {

}
