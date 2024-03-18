package cn.jia.base.service;

import cn.jia.base.entity.DictEntity;
import cn.jia.common.service.IBaseService;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface DictService extends IBaseService<DictEntity> {
    List<DictEntity> selectAll();

    List<DictEntity> selectAll(String lang);

    List<DictEntity> selectByType(String dictType, String lang);

    List<DictEntity> selectByParentId(String parentId, String lang);

    DictEntity selectByTypeAndValue(String dictType, String dictValue, String lang);

    DictEntity selectByTypeAndValue(String dictType, String dictValue);

    String getValue(String dictType, String dictValue, String lang);

    String getValue(String dictType, String dictValue);

    List<DictEntity> selectByTypeAndParentId(String dictType, String parentId, String lang);

    List<DictEntity> selectByParentIdAndValue(String parentId, String dictValue, String lang);
}
