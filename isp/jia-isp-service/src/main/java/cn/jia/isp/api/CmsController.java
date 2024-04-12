package cn.jia.isp.api;

import cn.jia.core.common.EsConstants;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.isp.common.IspErrorConstants;
import cn.jia.isp.entity.*;
import cn.jia.isp.service.CmsService;
import cn.jia.isp.service.FileService;
import cn.jia.oauth.service.ClientService;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 自定义表单接口
 *
 * @author chcbz
 * @since 2019年1月22日 下午8:23:45
 */
@RestController
@RequestMapping("/cms")
public class CmsController {

    @Inject
    private CmsService cmsService;
    @Autowired(required = false)
    private ClientService clientService;
    @Value("${security.oauth2.resource.id:jia-isp}")
    private String resourceId;
    @Inject
    private FileService fileService;

    /**
     * 表格列表
     *
     * @param page
     * @param request
     * @return
     */
    @RequestMapping(value = "/table/list", method = RequestMethod.POST)
    public Object listTable(@RequestBody JsonRequestPage<CmsTableEntity> page, HttpServletRequest request) {
        String clientId = EsSecurityHandler.checkClientId(request);
        CmsTableEntity example = page.getSearch() == null ? new CmsTableEntity() : page.getSearch();
        example.setClientId(clientId);
        PageInfo<CmsTableEntity> cmsList = cmsService.listTable(example, page.getPageNum(), page.getPageSize());
        JsonResultPage<CmsTableEntity> result = new JsonResultPage<>(cmsList.getList());
        result.setPageNum(cmsList.getPageNum());
        result.setTotal(cmsList.getTotal());
        return result;
    }

    /**
     * 获取表格信息
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/table/get", method = RequestMethod.GET)
    public Object findTableById(@RequestParam(name = "id") Long id) throws Exception {
        CmsTableEntity record = cmsService.findTable(id);
        if (record == null) {
            throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(record);
    }

    /**
     * 创建表格
     *
     * @param record
     * @return
     */
    @RequestMapping(value = "/table/create", method = RequestMethod.POST)
    public Object createTable(@RequestBody CmsTableDTO record) {
        cmsService.createTable(record);
        return JsonResult.success(record);
    }

    /**
     * 更新表格信息
     *
     * @param record
     * @return
     */
    @RequestMapping(value = "/table/update", method = RequestMethod.POST)
    public Object updateTable(@RequestBody CmsTableEntity record) {
        cmsService.updateTable(record);
        return JsonResult.success(record);
    }

    /**
     * 删除表格
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/table/delete", method = RequestMethod.GET)
    public Object deleteTable(@RequestParam(name = "id") Long id) {
        cmsService.deleteTable(id);
        return JsonResult.success();
    }

    /**
     * 数据列列表
     *
     * @param page
     * @param request
     * @return
     */
    @RequestMapping(value = "/column/list", method = RequestMethod.POST)
    public Object listColumn(@RequestBody JsonRequestPage<CmsColumnEntity> page, HttpServletRequest request) {
        EsSecurityHandler.checkClientId(request);
        CmsColumnEntity example = page.getSearch();
        PageInfo<CmsColumnEntity> cmsList = cmsService.listColumn(example, page.getPageNum(), page.getPageSize());
        JsonResultPage<CmsColumnEntity> result = new JsonResultPage<>(cmsList.getList());
        result.setPageNum(cmsList.getPageNum());
        result.setTotal(cmsList.getTotal());
        return result;
    }

    /**
     * 获取数据列信息
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/column/get", method = RequestMethod.GET)
    public Object findColumnById(@RequestParam(name = "id") Long id) throws Exception {
        CmsColumnEntity record = cmsService.findColumn(id);
        if (record == null) {
            throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(record);
    }

    /**
     * 创建数据列
     *
     * @param record
     * @return
     */
    @RequestMapping(value = "/column/create", method = RequestMethod.POST)
    public Object createColumn(@RequestBody CmsColumnEntity record) {
        cmsService.createColumn(record);
        return JsonResult.success(record);
    }

    /**
     * 更新数据列信息
     *
     * @param record
     * @return
     */
    @RequestMapping(value = "/column/update", method = RequestMethod.POST)
    public Object updateColumn(@RequestBody CmsColumnEntity record) {
        cmsService.updateColumn(record);
        return JsonResult.success(record);
    }

    /**
     * 删除数据列
     *
     * @param id
     * @return
     */
    @RequestMapping(value = "/column/delete", method = RequestMethod.GET)
    public Object deleteColumn(@RequestParam(name = "id") Long id) {
        cmsService.deleteColumn(id);
        return JsonResult.success();
    }

    /**
     * 获取配置信息
     *
     * @return
     */
    @RequestMapping(value = "/config/get", method = RequestMethod.GET)
    public Object findConfig() {
        CmsConfigEntity config = cmsService.selectConfig(EsContextHolder.getContext().getClientId());
        return JsonResult.success(config);
    }

    /**
     * 更新配置信息
     *
     * @param config
     * @return
     */
    @RequestMapping(value = "/config/update", method = RequestMethod.POST)
    public Object updateConfig(@RequestBody CmsConfigEntity config) {
        config.setClientId(EsContextHolder.getContext().getClientId());
        cmsService.updateConfig(config);
        return JsonResult.success(config);
    }

    /**
     * 注册服务
     *
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/register", method = RequestMethod.POST)
    public Object register(@RequestBody CmsConfigEntity config) throws Exception {
        //新增客户端资源
        clientService.addResource(resourceId, EsContextHolder.getContext().getClientId());
        //新增配置信息
        config.setClientId(EsContextHolder.getContext().getClientId());
        cmsService.createConfig(config);
        return JsonResult.success(config);
    }

    /**
     * 行数据列表
     *
     * @param page
     * @param request
     * @return
     */
    @RequestMapping(value = "/row/list", method = RequestMethod.POST)
    public Object listRow(@RequestBody JsonRequestPage<CmsRowExample> page, HttpServletRequest request) {
        String clientId = EsSecurityHandler.checkClientId(request);
        CmsRowExample example = page.getSearch() == null ? new CmsRowExample() : page.getSearch();
        example.setClientId(clientId);
        CmsConfigEntity config = cmsService.selectConfig(clientId);
        example.setName("cms_" + config.getTablePrefix() + "_" + example.getName());
        PageInfo<Map<String, Object>> cmsList = cmsService.listRow(example, page.getPageNum(), page.getPageSize());
        JsonResultPage<Map<String, Object>> result = new JsonResultPage<>(cmsList.getList());
        result.setPageNum(cmsList.getPageNum());
        result.setTotal(cmsList.getTotal());
        return result;
    }

    /**
     * 获取行数据信息
     *
     * @param cmsTable
     * @return
     */
    @RequestMapping(value = "/row/get", method = RequestMethod.POST)
    public Object findRowById(@RequestBody CmsRowDTO cmsTable, HttpServletRequest request) throws Exception {
        String clientId = EsSecurityHandler.checkClientId(request);
        cmsTable.setClientId(clientId);
        CmsConfigEntity config = cmsService.selectConfig(clientId);
        cmsTable.setTableName("cms_" + config.getTablePrefix() + "_" + cmsTable.getTableName());
        Map<String, Object> record = cmsService.findRow(cmsTable);
        if (record == null) {
            throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(record);
    }

    /**
     * 新增行数据
     *
     * @param record
     * @return
     */
    @RequestMapping(value = "/row/create", method = RequestMethod.POST)
    public Object createRow(@RequestBody CmsRowDTO record, HttpServletRequest request) {
        String clientId = EsSecurityHandler.checkClientId(request);
        record.setClientId(clientId);
        CmsTableEntity table = cmsService.findTableByName(record.getTableName());
        CmsConfigEntity config = cmsService.selectConfig(clientId);
        record.setTableName("cms_" + config.getTablePrefix() + "_" + record.getTableName());
        CmsColumnEntity example = new CmsColumnEntity();
        example.setTableId(table.getId());
        List<CmsColumnEntity> columns = cmsService.listColumn(example, 1, Integer.MAX_VALUE).getList();
        String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
        for (CmsColumnEntity c : columns) {
            for (CmsRowEntity r : record.getRows()) {
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
                    String fileName = DateUtil.formatDate(new Date(), "yyyyMMddHHmmss") + "_" +
                            DataUtil.getRandom(true, 8) + "." + extension;
                    if (ImgUtil.generateImage(rowValue, filePath + "/cms/" + fileName)) {
                        File file = new File(filePath + "/cms/" + fileName);
                        IspFileEntity upcf = new IspFileEntity();
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
        return JsonResult.success(record);
    }

    /**
     * 更新行数据信息
     *
     * @param record
     * @return
     */
    @RequestMapping(value = "/row/update", method = RequestMethod.POST)
    public Object updateRow(@RequestBody CmsRowDTO record, HttpServletRequest request) {
        String clientId = EsSecurityHandler.checkClientId(request);
        record.setClientId(clientId);
        CmsTableEntity table = cmsService.findTableByName(record.getTableName());
        CmsConfigEntity config = cmsService.selectConfig(clientId);
        record.setTableName("cms_" + config.getTablePrefix() + "_" + record.getTableName());
        Map<String, Object> row = cmsService.findRow(record);
        CmsColumnEntity example = new CmsColumnEntity();
        example.setTableId(table.getId());
        List<CmsColumnEntity> columns = cmsService.listColumn(example, 1, Integer.MAX_VALUE).getList();
        String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
        for (CmsColumnEntity c : columns) {
            for (CmsRowEntity r : record.getRows()) {
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
                    IspFileEntity cf = null;
                    if (StringUtils.isNotEmpty(fileName)) {
                        cf = fileService.findByUri(fileName);
                        FileUtil.delete(filePath + "/" + fileName);
                    }
                    if (StringUtils.isNotEmpty(fileName) && FileUtil.getExtension(fileName).equals(extension)) {
                        fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
                    } else {
                        fileName = DateUtil.formatDate(new Date(), "yyyyMMddHHmmss") + "_" +
                                DataUtil.getRandom(true, 8) + "." + extension;
                    }
                    if (ImgUtil.generateImage(rowValue, filePath + "/cms/" + fileName)) {
                        File file = new File(filePath + "/cms/" + fileName);
                        IspFileEntity upcf = new IspFileEntity();
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
        return JsonResult.success(record);
    }

    /**
     * 删除行数据
     *
     * @param record
     * @return
     */
    @RequestMapping(value = "/row/delete", method = RequestMethod.POST)
    public Object deleteRow(@RequestBody CmsRowDTO record, HttpServletRequest request) {
        String clientId = EsSecurityHandler.checkClientId(request);
        record.setClientId(clientId);
        CmsConfigEntity config = cmsService.selectConfig(clientId);
        record.setTableName("cms_" + config.getTablePrefix() + "_" + record.getTableName());
        cmsService.deleteRow(record);
        return JsonResult.success();
    }
}