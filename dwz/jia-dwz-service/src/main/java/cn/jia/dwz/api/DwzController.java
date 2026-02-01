package cn.jia.dwz.api;

import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.dwz.entity.DwzRecordEntity;
import cn.jia.dwz.service.DwzService;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.HtmlUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/dwz")
public class DwzController {

    @Inject
    private DwzService dwzService;

    /**
     * 处理短链接
     *
     * @param uri 地址
     * @return 跳转地址
     */
    @RequestMapping(value = "/view/{uri:.+}/**", method = RequestMethod.GET)
    public String view(@PathVariable String uri, HttpServletRequest request) {
        final String path = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE).toString();
        final String bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE).toString();
        String arguments = new AntPathMatcher().extractPathWithinPattern(bestMatchingPattern, path);

        if (!arguments.isEmpty()) {
            uri = uri + '/' + arguments;
        }
        DwzRecordEntity record;
        try {
            uri = HtmlUtils.htmlUnescape(URLDecoder.decode(uri, StandardCharsets.UTF_8));
            record = dwzService.view(uri);
            long now = DateUtil.nowTime();
            if (record.getExpireTime() < now) {
                throw new EsRuntimeException();
            }
        } catch (Exception e) {
            return "redirect:https://www.chaoyoufan.net/404.html";
        }
        return "redirect:" + record.getOrig();
    }

    /**
     * 获取短链接信息
     *
     * @param id 短链接ID
     * @return 短链接信息
     */
    @RequestMapping(value = "/get", method = RequestMethod.GET)
    @ResponseBody
    public Object findById(@RequestParam(name = "id") Long id) {
        DwzRecordEntity record = dwzService.get(id);
        return JsonResult.success(record);
    }

    /**
     * 生成短链接
     *
     * @param record 短链接信息
     * @return 短链接信息
     */
    @RequestMapping(value = "/gen", method = RequestMethod.POST)
    @ResponseBody
    public Object gen(@RequestBody DwzRecordEntity record) {
        String uri = dwzService.gen(record.getJiacn(), record.getOrig(), record.getExpireTime());
        return JsonResult.success(uri);
    }

    /**
     * 还原短链接
     *
     * @param uri 链接编码
     * @return 长链接
     */
    @RequestMapping(value = "/restore", method = RequestMethod.GET)
    @ResponseBody
    public Object restore(@RequestParam(name = "uri") String uri) {
        DwzRecordEntity record = dwzService.view(uri);
        return JsonResult.success(record.getOrig());
    }

    /**
     * 更新短链接信息
     *
     * @param record 短链接信息
     * @return 短链接信息
     */
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    @ResponseBody
    public Object update(@RequestBody DwzRecordEntity record) {
        dwzService.update(record);
        return JsonResult.success(record);
    }

    /**
     * 删除短链接
     *
     * @param id 短链接ID
     * @return 处理结果
     */
    @RequestMapping(value = "/delete", method = RequestMethod.DELETE)
    @ResponseBody
    public Object delete(@RequestParam(name = "id") Long id) {
        dwzService.delete(id);
        return JsonResult.success();
    }

    /**
     * 获取短链接列表
     *
     * @param page 搜索条件
     * @return 短链接列表
     */
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    @ResponseBody
    public Object list(@RequestBody JsonRequestPage<DwzRecordEntity> page) {
        PageInfo<DwzRecordEntity> list = dwzService.findPage(page.getSearch(), page.getPageNum(), page.getPageSize(), page.getOrderBy());
        JsonResultPage<DwzRecordEntity> result = new JsonResultPage<>(list.getList());
        result.setPageNum(list.getPageNum());
        result.setTotal(list.getTotal());
        return result;
    }
}
