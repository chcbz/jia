package cn.jia.isp.api;

import cn.jia.core.common.EsConstants;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.isp.common.ErrorConstants;
import cn.jia.isp.entity.*;
import cn.jia.isp.service.CmsService;
import cn.jia.isp.service.FileService;
import cn.jia.isp.service.OAuthService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 自定义表单接口
 * @author chcbz
 * @date 2019年1月22日 下午8:23:45
 */
@RestController
@RequestMapping("/cms")
public class CmsController {
	
	@Autowired
	private CmsService cmsService;
	@Autowired
	private OAuthService oAuthService;
	@Value("${security.oauth2.resource.id}")
	private String resourceId;
	@Autowired
	private FileService fileService;
	
	/**
	 * 表格列表
	 * @param page
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/table/list", method = RequestMethod.POST)
	public Object listTable(@RequestBody JSONRequestPage<CmsTable> page, HttpServletRequest request) {
		String clientId = EsSecurityHandler.checkClientId(request);
		CmsTable example = page.getSearch() == null ? new CmsTable() : page.getSearch();
		example.setClientId(clientId);
		Page<CmsTable> cmsList = cmsService.listTable(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(cmsList.getResult());
		result.setPageNum(cmsList.getPageNum());
		result.setTotal(cmsList.getTotal());
		return result;
	}
	
	/**
	 * 获取表格信息
	 * @param id
	 * @return
	 */
	@RequestMapping(value = "/table/get", method = RequestMethod.GET)
	public Object findTableById(@RequestParam(name = "id") Integer id) throws Exception {
		CmsTable record = cmsService.findTable(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建表格
	 * @param record
	 * @return
	 */
	@RequestMapping(value = "/table/create", method = RequestMethod.POST)
	public Object createTable(@RequestBody CmsTableDTO record) {
		cmsService.createTable(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新表格信息
	 * @param record
	 * @return
	 */
	@RequestMapping(value = "/table/update", method = RequestMethod.POST)
	public Object updateTable(@RequestBody CmsTable record) {
		cmsService.updateTable(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除表格
	 * @param id
	 * @return
	 */
	@RequestMapping(value = "/table/delete", method = RequestMethod.GET)
	public Object deleteTable(@RequestParam(name = "id") Integer id) {
		cmsService.deleteTable(id);
		return JSONResult.success();
	}
	
	/**
	 * 数据列列表
	 * @param page
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/column/list", method = RequestMethod.POST)
	public Object listColumn(@RequestBody JSONRequestPage<CmsColumn> page, HttpServletRequest request) {
		EsSecurityHandler.checkClientId(request);
		CmsColumn example = page.getSearch();
		Page<CmsColumn> cmsList = cmsService.listColumn(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(cmsList.getResult());
		result.setPageNum(cmsList.getPageNum());
		result.setTotal(cmsList.getTotal());
		return result;
	}
	
	/**
	 * 获取数据列信息
	 * @param id
	 * @return
	 */
	@RequestMapping(value = "/column/get", method = RequestMethod.GET)
	public Object findColumnById(@RequestParam(name = "id") Integer id) throws Exception {
		CmsColumn record = cmsService.findColumn(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建数据列
	 * @param record
	 * @return
	 */
	@RequestMapping(value = "/column/create", method = RequestMethod.POST)
	public Object createColumn(@RequestBody CmsColumn record) {
		cmsService.createColumn(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新数据列信息
	 * @param record
	 * @return
	 */
	@RequestMapping(value = "/column/update", method = RequestMethod.POST)
	public Object updateColumn(@RequestBody CmsColumn record) {
		cmsService.updateColumn(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除数据列
	 * @param id
	 * @return
	 */
	@RequestMapping(value = "/column/delete", method = RequestMethod.GET)
	public Object deleteColumn(@RequestParam(name = "id") Integer id) {
		cmsService.deleteColumn(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取配置信息
	 * @return
	 */
	@RequestMapping(value = "/config/get", method = RequestMethod.GET)
	public Object findConfig() {
		CmsConfig config = cmsService.selectConfig(EsSecurityHandler.clientId());
		return JSONResult.success(config);
	}
	
	/**
	 * 更新配置信息
	 * @param config
	 * @return
	 */
	@RequestMapping(value = "/config/update", method = RequestMethod.POST)
	public Object updateConfig(@RequestBody CmsConfig config) {
		config.setClientId(EsSecurityHandler.clientId());
		cmsService.updateConfig(config);
		return JSONResult.success(config);
	}
	
	/**
	 * 注册服务
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = "/register", method = RequestMethod.POST)
	public Object register(@RequestBody CmsConfig config) throws Exception {
		//新增客户端资源
		JSONResult<Map<String, Object>> resourceResult = oAuthService.addResource(resourceId);
		if(!ErrorConstants.SUCCESS.equals(resourceResult.getCode())) {
			throw new EsRuntimeException(resourceResult.getCode());
		}
		//新增配置信息
		config.setClientId(EsSecurityHandler.clientId());
		cmsService.createConfig(config);
		return JSONResult.success(config);
	}
	
	/**
	 * 行数据列表
	 * @param page
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/row/list", method = RequestMethod.POST)
	public Object listRow(@RequestBody JSONRequestPage<CmsRowExample> page, HttpServletRequest request) {
		String clientId = EsSecurityHandler.checkClientId(request);
		CmsRowExample example = page.getSearch() == null ? new CmsRowExample() : page.getSearch();
		example.setClientId(clientId);
		CmsConfig config = cmsService.selectConfig(clientId);
		example.setName("cms_" + config.getTablePrefix() + "_" + example.getName());
		Page<Map<String, Object>> cmsList = cmsService.listRow(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(cmsList.getResult());
		result.setPageNum(cmsList.getPageNum());
		result.setTotal(cmsList.getTotal());
		return result;
	}
	
	/**
	 * 获取行数据信息
	 * @param cmsTable
	 * @return
	 */
	@RequestMapping(value = "/row/get", method = RequestMethod.POST)
	public Object findRowById(@RequestBody CmsRowDTO cmsTable, HttpServletRequest request) throws Exception {
        String clientId = EsSecurityHandler.checkClientId(request);
		cmsTable.setClientId(clientId);
		CmsConfig config = cmsService.selectConfig(clientId);
		cmsTable.setTableName("cms_" + config.getTablePrefix() + "_" + cmsTable.getTableName());
		Map<String,Object> record = cmsService.findRow(cmsTable);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 新增行数据
	 * @param record
	 * @return
	 */
	@RequestMapping(value = "/row/create", method = RequestMethod.POST)
	public Object createRow(@RequestBody CmsRowDTO record, HttpServletRequest request) {
		String clientId = EsSecurityHandler.checkClientId(request);
		record.setClientId(clientId);
		CmsTable table = cmsService.findTableByName(record.getTableName());
		CmsConfig config = cmsService.selectConfig(clientId);
		record.setTableName("cms_" + config.getTablePrefix() + "_" + record.getTableName());
		CmsColumn example = new CmsColumn();
		example.setTableId(table.getId());
		List<CmsColumn> columns = cmsService.listColumn(example, 1, Integer.MAX_VALUE).getResult();
		String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
		for (CmsColumn c : columns) {
			for (CmsRow r : record.getRows()) {
				String rowValue = r.getValue();
				if (c.getName().equals(r.getName()) && "image".equals(c.getType()) && StringUtils.isNotBlank(rowValue)) {
					String extension = "png";
					if (rowValue.startsWith("data:")) {
						extension = rowValue.substring(rowValue.indexOf("/") + 1, rowValue.indexOf(";"));
						rowValue = rowValue.substring(rowValue.indexOf(",") + 1);
					}
					File pathFile = new File(filePath + "/cms");
					//noinspection ResultOfMethodCallIgnored
					pathFile.mkdirs();
					String fileName = DateUtil.formatDate(new Date(), "yyyyMMddHHmmss") + "_" + DataUtil.getRandom(true, 8) + "." + extension;
					if (ImgUtil.GenerateImage(rowValue, filePath + "/cms/" + fileName)) {
						File file = new File(filePath + "/cms/" + fileName);
						IspFile upcf = new IspFile();
						upcf.setClientId(clientId);
						upcf.setExtension(extension);
						upcf.setName(fileName);
						upcf.setSize(file.length());
						upcf.setType(EsConstants.FILE_TYPE_CMS);
						upcf.setUri("cms/" + fileName);
						fileService.create(upcf);
					}
					r.setValue("cms/" + fileName);
				}
			}
		}
		cmsService.createRow(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新行数据信息
	 * @param record
	 * @return
	 */
	@RequestMapping(value = "/row/update", method = RequestMethod.POST)
	public Object updateRow(@RequestBody CmsRowDTO record, HttpServletRequest request) {
		String clientId = EsSecurityHandler.checkClientId(request);
		record.setClientId(clientId);
		CmsTable table = cmsService.findTableByName(record.getTableName());
		CmsConfig config = cmsService.selectConfig(clientId);
		record.setTableName("cms_" + config.getTablePrefix() + "_" + record.getTableName());
		Map<String, Object> row = cmsService.findRow(record);
		CmsColumn example = new CmsColumn();
		example.setTableId(table.getId());
		List<CmsColumn> columns = cmsService.listColumn(example, 1, Integer.MAX_VALUE).getResult();
		String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
		for (CmsColumn c : columns) {
			for (CmsRow r : record.getRows()) {
				String rowValue = r.getValue();
				if (c.getName().equals(r.getName()) && "image".equals(c.getType()) && StringUtils.isNotBlank(rowValue)) {
					String extension = "png";
					if (rowValue.startsWith("data:")) {
						extension = rowValue.substring(rowValue.indexOf("/") + 1, rowValue.indexOf(";"));
						rowValue = rowValue.substring(rowValue.indexOf(",") + 1);
					}
					File pathFile = new File(filePath + "/cms");
					//noinspection ResultOfMethodCallIgnored
					pathFile.mkdirs();
					String fileName = StringUtils.valueOf(row.get(c.getName()));
					IspFile cf = null;
					if (StringUtils.isNotEmpty(fileName)) {
						cf = fileService.findByUri(fileName);
						FileUtil.delete(filePath + "/" + fileName);
					}
					if (StringUtils.isNotEmpty(fileName) && FileUtil.getExtension(fileName).equals(extension)) {
						fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
					} else {
						fileName = DateUtil.formatDate(new Date(), "yyyyMMddHHmmss") + "_" + DataUtil.getRandom(true, 8) + "." + extension;
					}
					if (ImgUtil.GenerateImage(rowValue, filePath + "/cms/" + fileName)) {
						File file = new File(filePath + "/cms/" + fileName);
						IspFile upcf = new IspFile();
						upcf.setClientId(clientId);
						upcf.setExtension(extension);
						upcf.setName(fileName);
						upcf.setSize(file.length());
						upcf.setType(EsConstants.FILE_TYPE_CMS);
						upcf.setUri("cms/" + fileName);
						if (cf == null) {
							fileService.create(upcf);
						} else {
							upcf.setId(cf.getId());
							fileService.update(upcf);
						}
					}
					r.setValue("cms/" + fileName);
				}
			}
		}
		cmsService.updateRow(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除行数据
	 * @param record
	 * @return
	 */
	@RequestMapping(value = "/row/delete", method = RequestMethod.POST)
	public Object deleteRow(@RequestBody CmsRowDTO record, HttpServletRequest request) {
		String clientId = EsSecurityHandler.checkClientId(request);
		record.setClientId(clientId);
		CmsConfig config = cmsService.selectConfig(clientId);
		record.setTableName("cms_" + config.getTablePrefix() + "_" + record.getTableName());
		cmsService.deleteRow(record);
		return JSONResult.success();
	}
}