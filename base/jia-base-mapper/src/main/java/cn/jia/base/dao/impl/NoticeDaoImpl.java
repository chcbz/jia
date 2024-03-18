package cn.jia.base.dao.impl;

import cn.jia.base.dao.NoticeDao;
import cn.jia.base.entity.NoticeEntity;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.base.mapper.NoticeMapper;
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
public class NoticeDaoImpl extends BaseDaoImpl<NoticeMapper, NoticeEntity> implements NoticeDao {

}
