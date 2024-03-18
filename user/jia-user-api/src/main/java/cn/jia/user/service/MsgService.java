package cn.jia.user.service;

import cn.jia.common.service.IBaseService;
import cn.jia.user.entity.MsgEntity;
import com.github.pagehelper.PageInfo;

public interface MsgService extends IBaseService<MsgEntity> {

	void recycle(Long id);

	void restore(Long id);

	void readAll(Long userId);
}
