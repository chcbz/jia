package cn.jia.core.service;

import java.util.List;

import com.github.pagehelper.Page;

import cn.jia.core.entity.Dict;

public interface DictService {

	public Dict selectById(Integer id);

	public void insert(Dict dict);

	public void update(Dict dict);

	public void delete(Integer id);

	public List<Dict> selectAll(String lang);

	public List<Dict> selectByDictType(String dictType, String lang);

	public List<Dict> selectByParentId(String parentId, String lang);

	public Dict selectByDictTypeAndDictValue(String dictType, String dictValue, String lang);
	
	public Dict selectByDictTypeAndDictValue(String dictType, String dictValue);

	public List<Dict> selectByDictTypeAndParentId(String dictType, String parentId, String lang);

	public List<Dict> selectByParentIdAndDictValue(String parentId, String dictValue, String lang);
	
	public Page<Dict> findByExamplePage(Dict dict, int pageNo, int pageSize);
	
	public void upsert(Dict dict);
}
