package cn.jia.core.service;

import cn.jia.core.entity.Dict;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtils;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
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
public class DictService {

	private final static String CACHE_KEY = "DICT_LIST";
	
	@Autowired
	private IDictService dictService;
	@Resource
	private RedisTemplate<String, List<Dict>> redisTemplate;
	@Value("${dict.cache.time:300}")
	private Long cacheTime;

	public Dict selectById(Integer id) {
		return dictService.getById(id);
	}

	public void insert(Dict dict) {
		Long now = DateUtil.genTime(new Date());
		dict.setCreateTime(now);
		dict.setUpdateTime(now);
		dictService.save(dict);
	}

	public void update(Dict dict) {
		Long now = DateUtil.genTime(new Date());
		dict.setUpdateTime(now);
		dictService.updateById(dict);
	}

	public void delete(Integer id) {
		dictService.removeById(id);
	}

	public List<Dict> selectAll() {
		List<Dict> dictList = redisTemplate.opsForValue().get(CACHE_KEY);
		if (CollectionUtils.isEmpty(dictList)) {
			dictList = dictService.list();
			redisTemplate.opsForValue().set(CACHE_KEY, dictList, cacheTime, TimeUnit.SECONDS);
		}
		return dictList;
	}

	public List<Dict> selectAll(String lang) {
		return selectAll().stream().filter(dict -> StringUtils.isEmpty(dict.getLanguage()) || lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	public List<Dict> selectByType(String dictType, String lang) {
		return selectAll().stream().filter(dict -> dictType.equals(dict.getType()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	public List<Dict> selectByParentId(String parentId, String lang) {
		return selectAll().stream().filter(dict -> parentId.equals(dict.getParentId()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	public Dict selectByTypeAndValue(String dictType, String dictValue, String lang) {
		List<Dict> list = selectAll().stream().filter(dict -> dictType.equals(dict.getType()) && dictValue.equals(dict.getValue()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
		if(list.size() == 0) {
			Dict example = new Dict();
			example.setType(dictType);
			example.setValue(dictValue);
			example.setLanguage(lang);
			list = dictService.listByEntity(example);
		}
		return list.get(0);
	}
	
	public Dict selectByTypeAndValue(String dictType, String dictValue) {
		List<Dict> list = selectAll().stream().filter(dict -> dictType.equals(dict.getType()) && dictValue.equals(dict.getValue())).collect(Collectors.toList());
		if(list.size() == 0) {
			Dict example = new Dict();
			example.setType(dictType);
			example.setValue(dictValue);
			list = dictService.listByEntity(example);
		}
		return list.get(0);
	}

	public String getValue(String dictType, String dictValue, String lang) {
		return selectByTypeAndValue(dictType, dictValue, lang).getName();
	}

	public String getValue(String dictType, String dictValue) {
		return selectByTypeAndValue(dictType, dictValue).getName();
	}

	public List<Dict> selectByTypeAndParentId(String dictType, String parentId, String lang) {
		return selectAll().stream().filter(dict -> dictType.equals(dict.getType()) && parentId.equals(dict.getParentId()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	public List<Dict> selectByParentIdAndValue(String parentId, String dictValue, String lang) {
		return selectAll().stream().filter(dict -> dictValue.equals(dict.getValue()) && parentId.equals(dict.getParentId()) && lang.equals(dict.getLanguage())).collect(Collectors.toList());
	}

	public PageInfo<Dict> findByExamplePage(Dict dict, int pageNo, int pageSize) {
		PageHelper.startPage(pageNo, pageSize);
		List<Dict> kefuFaqList = dictService.listByEntity(dict);
		return PageInfo.of(kefuFaqList);
	}

	public void upsert(Dict dict) {
		Long now = DateUtil.genTime(new Date());
		dict.setCreateTime(now);
		dict.setUpdateTime(now);
		dictService.saveOrUpdate(dict);
	}

}
