package cn.jia.base.api;

import cn.jia.base.entity.DictEntity;
import cn.jia.base.service.impl.DictServiceImpl;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.JsonUtil;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dict")
public class DictController {

    @Autowired
    private DictServiceImpl dictService;

    /**
     * 根据类型获取数据字典列表
     *
     * @param id
     * @return
     */
    @PreAuthorize("hasAuthority('dict-get')")
    @RequestMapping(value = "/get", method = RequestMethod.GET)
    public Object find(@RequestParam(name = "id") Long id) {
        DictEntity dict = dictService.get(id);
        return JsonResult.success(dict);
    }

    /**
     * 创建数据字典
     *
     * @param dict
     * @return
     */
    @PreAuthorize("hasAuthority('dict-create')")
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public Object create(@RequestBody DictEntity dict) {
        dictService.create(dict);
        return JsonResult.success();
    }

    /**
     * 更新数据字典信息
     *
     * @param dict
     * @return
     */
    @PreAuthorize("hasAuthority('dict-update')")
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public Object update(@RequestBody DictEntity dict) {
        dictService.update(dict);
        return JsonResult.success();
    }

    /**
     * 数据字典列表
     *
     * @param page
     * @return
     */
    @PreAuthorize("hasAuthority('dict-list')")
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public Object list(@RequestBody JsonRequestPage<String> page) {
        DictEntity dict = JsonUtil.fromJson(page.getSearch(), DictEntity.class);
        PageInfo<DictEntity> dictList = dictService.findPage(dict, page.getPageSize(), page.getPageNum());
        JsonResultPage<DictEntity> result = new JsonResultPage<>(dictList.getList());
        result.setPageNum(dictList.getPageNum());
        result.setTotal(dictList.getTotal());
        return result;
    }

    /**
     * *
     *
     * @param id
     * @return
     */
    @PreAuthorize("hasAuthority('dict-delete')")
    @RequestMapping(value = "/delete", method = RequestMethod.GET)
    public Object delete(@RequestParam("id") Long id) {
        dictService.delete(id);
        return JsonResult.success();
    }

    /**
     * 获取当前语言下的所有数据字典
     *
     * @return
     */
    @PreAuthorize("hasAuthority('dict-locale')")
    @RequestMapping(value = "/locale", method = RequestMethod.GET)
    public Object locale() {
        return JsonResult.success(dictService.selectAll(LocaleContextHolder.getLocale().toString()));
    }

    /**
     * 清除缓存
     *
     * @return
     */
    @RequestMapping(value = "/cleanCache", method = RequestMethod.GET)
    public Object cleanCache() {
        List<DictEntity> dictList = dictService.selectAll(LocaleContextHolder.getLocale().toString());
        if (dictList.size() > 0) {
            DictEntity dict = dictList.get(0);
            DictEntity upDict = new DictEntity();
            upDict.setId(dict.getId());
            upDict.setUpdateTime(DateUtil.nowTime());
            dictService.update(upDict);
        }
        return JsonResult.success();
    }
}
