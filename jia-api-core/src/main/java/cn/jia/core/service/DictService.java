package cn.jia.core.service;

import cn.jia.core.entity.Dict;
import com.github.pagehelper.Page;

import java.util.List;

public interface DictService {

	Dict selectById(Integer id);

	void insert(Dict dict);

	void update(Dict dict);

	void delete(Integer id);

	List<Dict> selectAll();

	List<Dict> selectAll(String lang);

	List<Dict> selectByType(String dictType, String lang);

	List<Dict> selectByParentId(String parentId, String lang);

	Dict selectByTypeAndValue(String dictType, String dictValue, String lang);
	
	Dict selectByTypeAndValue(String dictType, String dictValue);

	String getValue(String dictType, String dictValue, String lang);

	String getValue(String dictType, String dictValue);

	List<Dict> selectByTypeAndParentId(String dictType, String parentId, String lang);

	List<Dict> selectByParentIdAndValue(String parentId, String dictValue, String lang);
	
	Page<Dict> findByExamplePage(Dict dict, int pageNo, int pageSize);
	
	void upsert(Dict dict);
}
