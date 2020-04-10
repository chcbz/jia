package cn.jia.user.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.pagehelper.Page;

import cn.jia.core.entity.Dict;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.service.DictService;
import cn.jia.core.util.JSONUtil;

@RestController
@RequestMapping("/dict")
public class DictController {
	
	@Autowired
	private DictService dictService;
	
	/**
	 * 根据类型获取数据字典列表
	 * 
	 * @param type
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@RequestMapping("/get")
	public Object find(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		Dict dict = dictService.selectById(id);
		return JSONResult.success(dict);
	}
	
	/**
	 * 创建数据字典
	 * @param dict
	 * @return
	 */
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
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody Dict dict) {
		dictService.update(dict);
		return JSONResult.success();
	}
	
	/**
	 * 数据字典列表
	 * @param page
	 * @return
	 * @throws Exception
	 */
	@RequestMapping("/list")
	public Object list(@RequestBody JSONRequestPage<String> page) throws Exception {
		Dict dict = JSONUtil.fromJson(page.getSearch(), Dict.class);
		Page<Dict> dictList = dictService.findByExamplePage(dict, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(dictList.getResult());
		result.setPageNum(dictList.getPageNum());
		result.setTotal(dictList.getTotal());
		return result;
	}

	/**
	 * 删除数据字典
	 * 
	 * @param id
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@RequestMapping("/delete")
	public Object delete(@RequestParam("id") Integer id) throws Exception {
		dictService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取当前语言下的所有数据字典
	 * @return
	 */
	@RequestMapping(value = "/locale", method = RequestMethod.GET)
	public Object locale() {
		return JSONResult.success(dictService.selectAll(LocaleContextHolder.getLocale().toString()));
	}
}
