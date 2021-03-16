package cn.jia.core.service.impl;

import cn.jia.core.dao.DictMapper;
import cn.jia.core.entity.Dict;
import cn.jia.core.service.DictService;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtils;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class DictServiceImpl implements DictService {

	private final static String CACHE_KEY = "DICT_LIST";
	
	@Autowired
	private DictMapper dictMapper;
	@Autowired
	private RedisTemplate<String, List<Dict>> redisTemplate;
	@Value("${dict.cache.time:300}")
	private Long cacheTime;

	@Override
	public Dict selectById(Integer id) {
		return dictMapper.selectByPrimaryKey(id);
	}

	@Override
	public void insert(Dict dict) {
		Long now = DateUtil.genTime(new Date());
		dict.setCreateTime(now);
		dict.setUpdateTime(now);
		dictMapper.insertSelective(dict);
	}

	@Override
	public void update(Dict dict) {
		Long now = DateUtil.genTime(new Date());
		dict.setUpdateTime(now);
		dictMapper.updateByPrimaryKeySelective(dict);
	}

	@Override
	public void delete(Integer id) {
		dictMapper.deleteByPrimaryKey(id);
	}

	@Override
	public List<Dict> selectAll() {
		List<Dict> dictList = redisTemplate.opsForValue().get(CACHE_KEY);
		if (CollectionUtils.isEmpty(dictList)) {
			dictList = dictMapper.selectAll();
			redisTemplate.opsForValue().set(CACHE_KEY, dictList, cacheTime, TimeUnit.SECONDS);
		}
		return dictList;
	}

	@Override
	public List<Dict> selectAll(String lang) {
		return selectAll().stream().filter(dict -> StringUtils.isEmpty(dict.getLanguage()) || lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	@Override
	public List<Dict> selectByType(String dictType, String lang) {
		return selectAll().stream().filter(dict -> dictType.equals(dict.getType()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	@Override
	public List<Dict> selectByParentId(String parentId, String lang) {
		return selectAll().stream().filter(dict -> parentId.equals(dict.getParentId()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	@Override
	public Dict selectByTypeAndValue(String dictType, String dictValue, String lang) {
		List<Dict> list = selectAll().stream().filter(dict -> dictType.equals(dict.getType()) && dictValue.equals(dict.getValue()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
		if(list.size() == 0) {
			Dict example = new Dict();
			example.setType(dictType);
			example.setValue(dictValue);
			example.setLanguage(lang);
			list = dictMapper.findByExamplePage(example);
		}
		return list.get(0);
	}
	
	@Override
	public Dict selectByTypeAndValue(String dictType, String dictValue) {
		List<Dict> list = selectAll().stream().filter(dict -> dictType.equals(dict.getType()) && dictValue.equals(dict.getValue())).collect(Collectors.toList());
		if(list.size() == 0) {
			Dict example = new Dict();
			example.setType(dictType);
			example.setValue(dictValue);
			list = dictMapper.findByExamplePage(example);
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
	public List<Dict> selectByTypeAndParentId(String dictType, String parentId, String lang) {
		return selectAll().stream().filter(dict -> dictType.equals(dict.getType()) && parentId.equals(dict.getParentId()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	@Override
	public List<Dict> selectByParentIdAndValue(String parentId, String dictValue, String lang) {
		return selectAll().stream().filter(dict -> dictValue.equals(dict.getValue()) && parentId.equals(dict.getParentId()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	@Override
	public Page<Dict> findByExamplePage(Dict dict, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		return dictMapper.findByExamplePage(dict);
	}

	@Override
	public void upsert(Dict dict) {
		Long now = DateUtil.genTime(new Date());
		dict.setCreateTime(now);
		dict.setUpdateTime(now);
		dictMapper.upsert(dict);
	}

}
