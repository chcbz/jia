package cn.jia.core;

import cn.jia.core.entity.Dict;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.service.DictService;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.JSONUtil;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/dict")
public class DictController {
	
	@Autowired
	private DictService dictService;

	/**
	 * 根据类型获取数据字典列表
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('dict-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object find(@RequestParam(name = "id") Integer id) {
		Dict dict = dictService.selectById(id);
		return JSONResult.success(dict);
	}
	
	/**
	 * 创建数据字典
	 * @param dict
	 * @return
	 */
	@PreAuthorize("hasAuthority('dict-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody Dict dict) {
		dictService.insert(dict);
		return JSONResult.success();
	}

	/**
	 * 更新数据字典信息
	 * @param dict
	 * @return
	 */
	@PreAuthorize("hasAuthority('dict-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody Dict dict) {
		dictService.update(dict);
		return JSONResult.success();
	}
	
	/**
	 * 数据字典列表
	 * @param page
	 * @return
	 */
	@PreAuthorize("hasAuthority('dict-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Dict dict = JSONUtil.fromJson(page.getSearch(), Dict.class);
		Page<Dict> dictList = dictService.findByExamplePage(dict, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(dictList.getResult());
		result.setPageNum(dictList.getPageNum());
		result.setTotal(dictList.getTotal());
		return result;
	}

	/**
	 * 	 *
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('dict-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam("id") Integer id) {
		dictService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取当前语言下的所有数据字典
	 * @return
	 */
	@PreAuthorize("hasAuthority('dict-locale')")
	@RequestMapping(value = "/locale", method = RequestMethod.GET)
	public Object locale() {
		return JSONResult.success(dictService.selectAll(LocaleContextHolder.getLocale().toString()));
	}

	/**
	 * 清除缓存
	 * @return
	 */
	@RequestMapping(value = "/cleanCache", method = RequestMethod.GET)
	public Object cleanCache() {
		List<Dict> dictList = dictService.selectAll(LocaleContextHolder.getLocale().toString());
		if(dictList.size() > 0){
			Dict dict = dictList.get(0);
			Dict upDict = new Dict();
			upDict.setId(dict.getId());
			upDict.setUpdateTime(DateUtil.genTime(new Date()));
			dictService.update(upDict);
		}
		return JSONResult.success();
	}
}
