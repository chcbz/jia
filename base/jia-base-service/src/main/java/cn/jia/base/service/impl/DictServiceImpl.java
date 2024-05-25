package cn.jia.base.service.impl;

import cn.jia.base.dao.DictDao;
import cn.jia.base.entity.DictEntity;
import cn.jia.base.service.DictService;
import cn.jia.core.redis.RedisService;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import jakarta.annotation.Resource;
import jakarta.inject.Named;
import org.springframework.beans.factory.annotation.Value;
import cn.jia.core.util.CollectionUtil;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Named
public class DictServiceImpl extends BaseServiceImpl<DictDao, DictEntity> implements DictService {
    private final static String CACHE_KEY = "DICT_LIST";

    @Resource
    private RedisService redisService;
    @Value("${dict.cache.time:300}")
    private long cacheTime;

    @Override
    public List<DictEntity> selectAll() {
        List<DictEntity> dictEntityList = JsonUtil.jsonToList(redisService.get(CACHE_KEY), DictEntity.class);
        if (CollectionUtil.isNullOrEmpty(dictEntityList)) {
            dictEntityList = baseDao.selectAll();
            redisService.set(CACHE_KEY, JsonUtil.toJson(dictEntityList), cacheTime, TimeUnit.SECONDS);
        }
        return dictEntityList;
    }

    @Override
    public List<DictEntity> selectAll(String lang) {
        return selectAll().stream().filter(dictEntity -> StringUtil.isEmpty(dictEntity.getLanguage()) ||
                lang.equals(dictEntity.getLanguage())).collect(Collectors.toList());
    }

    @Override
    public List<DictEntity> selectByType(String dictType, String lang) {
        return selectAll().stream().filter(dictEntity -> dictType.equals(dictEntity.getType()) &&
                lang.equals(dictEntity.getLanguage())).collect(Collectors.toList());
    }

    @Override
    public List<DictEntity> selectByParentId(String parentId, String lang) {
        return selectAll().stream().filter(dictEntity -> parentId.equals(dictEntity.getParentId()) &&
                lang.equals(dictEntity.getLanguage())).collect(Collectors.toList());
    }

    @Override
    public DictEntity selectByTypeAndValue(String dictType, String dictValue, String lang) {
        List<DictEntity> list = selectAll().stream().filter(dictEntity -> dictType.equals(dictEntity.getType()) &&
                        dictValue.equals(dictEntity.getValue()) && lang.equals(dictEntity.getLanguage()))
                .collect(Collectors.toList());
        if (list.size() == 0) {
            DictEntity example = new DictEntity();
            example.setType(dictType);
            example.setValue(dictValue);
            example.setLanguage(lang);
            list = baseDao.selectByEntity(example);
        }
        return list.get(0);
    }

    @Override
    public DictEntity selectByTypeAndValue(String dictType, String dictValue) {
        List<DictEntity> list = selectAll().stream().filter(dictEntity -> dictType.equals(dictEntity.getType()) &&
                dictValue.equals(dictEntity.getValue())).collect(Collectors.toList());
        if (list.size() == 0) {
            DictEntity example = new DictEntity();
            example.setType(dictType);
            example.setValue(dictValue);
            list = baseDao.selectByEntity(example);
        }
        return list.get(0);
    }

    @Override
    public String getValue(String dictType, String dictValue, String lang) {
        return selectByTypeAndValue(dictType, dictValue, lang).getName();
    }

    @Override
    public String getValue(String dictType, String dictValue) {
        return selectByTypeAndValue(dictType, dictValue).getName();
    }

    @Override
    public List<DictEntity> selectByTypeAndParentId(String dictType, String parentId, String lang) {
        return selectAll().stream().filter(dictEntity -> dictType.equals(dictEntity.getType()) &&
                        parentId.equals(dictEntity.getParentId()) && lang.equals(dictEntity.getLanguage()))
                .collect(Collectors.toList());
    }

    @Override
    public List<DictEntity> selectByParentIdAndValue(String parentId, String dictValue, String lang) {
        return selectAll().stream().filter(dictEntity -> dictValue.equals(dictEntity.getValue()) &&
                        parentId.equals(dictEntity.getParentId()) && lang.equals(dictEntity.getLanguage()))
                .collect(Collectors.toList());
    }

}
