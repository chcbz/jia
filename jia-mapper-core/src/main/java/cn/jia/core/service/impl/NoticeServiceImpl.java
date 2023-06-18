package cn.jia.core.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.core.entity.Notice;
import cn.jia.core.mapper.NoticeMapper;
import cn.jia.core.service.INoticeService;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2023-06-18
 */
@Named
public class NoticeServiceImpl extends BaseServiceImpl<NoticeMapper, Notice> implements INoticeService {

}
