package cn.jia.kefu.api;

import cn.jia.core.annotation.SysPermission;
import cn.jia.core.common.EsConstants;
import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.FileUtil;
import cn.jia.isp.common.IspConstants;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.service.FileService;
import cn.jia.kefu.entity.KefuFaqEntity;
import cn.jia.kefu.entity.KefuMessageEntity;
import cn.jia.kefu.service.KefuFaqService;
import cn.jia.kefu.service.KefuMessageService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * 客服接口
 *
 * @author chc
 */
@RestController
@RequestMapping("/kefu")
@SysPermission(name = "kefu")
public class KefuController {

    @Autowired
    private KefuFaqService kefuFaqService;
    @Autowired
    private KefuMessageService kefuMessageService;
    @Autowired(required = false)
    private FileService fileService;

    /**
     * FAQ列表
     *
     * @param page 查询条件
     * @return FAQ列表
     */
    @PreAuthorize("hasAuthority('kefu-faq_list')")
    @RequestMapping(value = "/faq/list", method = RequestMethod.POST)
    public Object listFaq(@RequestBody JsonRequestPage<KefuFaqEntity> page) {
        KefuFaqEntity example = page.getSearch();
        if (example == null) {
            example = new KefuFaqEntity();
        }
        example.setClientId(EsContextHolder.getContext().getClientId());
        PageInfo<KefuFaqEntity> list = kefuFaqService.findPage(example, page.getPageSize(), page.getPageNum(), page.getOrderBy());
        JsonResultPage<KefuFaqEntity> result = new JsonResultPage<>(list.getList());
        result.setPageNum(list.getPageNum());
        result.setTotal(list.getTotal());
        return result;
    }

    /**
     * 获取FAQ信息
     *
     * @param id FAQID
     * @return FAQ信息
     */
    @PreAuthorize("hasAuthority('kefu-faq_get')")
    @RequestMapping(value = "/faq/get", method = RequestMethod.GET)
    public Object findFaqById(@RequestParam(name = "id") Long id) throws Exception {
        KefuFaqEntity record = kefuFaqService.get(id);
        if (record == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(record);
    }

    /**
     * 创建FAQ
     *
     * @param record FAQ信息
     * @return FAQ信息
     */
    @PreAuthorize("hasAuthority('kefu-faq_create')")
    @RequestMapping(value = "/faq/create", method = RequestMethod.POST)
    public Object createFaq(@RequestBody KefuFaqEntity record) {
        record.setClientId(EsContextHolder.getContext().getClientId());
        kefuFaqService.create(record);
        return JsonResult.success(record);
    }

    /**
     * 更新FAQ信息
     *
     * @param record FAQ信息
     * @return FAQ信息
     */
    @PreAuthorize("hasAuthority('kefu-faq_update')")
    @RequestMapping(value = "/faq/update", method = RequestMethod.POST)
    public Object updateFaq(@RequestBody KefuFaqEntity record) {
        kefuFaqService.update(record);
        return JsonResult.success(record);
    }

    /**
     * 删除FAQ
     *
     * @param id FAQID
     * @return 处理结果
     */
    @PreAuthorize("hasAuthority('kefu-faq_delete')")
    @RequestMapping(value = "/faq/delete", method = RequestMethod.GET)
    public Object deleteFaq(@RequestParam(name = "id") Long id) throws Exception {
        KefuFaqEntity record = kefuFaqService.get(id);
        if (record == null || !Objects.equals(EsContextHolder.getContext().getClientId(), record.getClientId())) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        kefuFaqService.delete(id);
        return JsonResult.success();
    }

    /**
     * 消息列表
     *
     * @param page 查询条件
     * @return 消息列表
     */
    @PreAuthorize("hasAuthority('kefu-message_list')")
    @RequestMapping(value = "/message/list", method = RequestMethod.POST)
    public Object listMessage(@RequestBody JsonRequestPage<KefuMessageEntity> page) {
        KefuMessageEntity example = page.getSearch();
        if (example == null) {
            example = new KefuMessageEntity();
        }
        example.setClientId(EsContextHolder.getContext().getClientId());
        PageInfo<KefuMessageEntity> list =
                kefuMessageService.findPage(example, page.getPageSize(), page.getPageNum(), page.getOrderBy());
        JsonResultPage<KefuMessageEntity> result = new JsonResultPage<>(list.getList());
        result.setPageNum(list.getPageNum());
        result.setTotal(list.getTotal());
        return result;
    }

    /**
     * 获取消息信息
     *
     * @param id 消息ID
     * @return 消息信息
     */
    @PreAuthorize("hasAuthority('kefu-message_get')")
    @RequestMapping(value = "/message/get", method = RequestMethod.GET)
    public Object findMessageById(@RequestParam(name = "id") Long id) throws Exception {
        KefuMessageEntity record = kefuMessageService.get(id);
        if (record == null) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(record);
    }

    /**
     * 创建消息
     *
     * @param record 消息信息
     * @return 消息信息
     */
    @RequestMapping(value = "/message/create", method = RequestMethod.POST)
    public Object createMessage(@RequestPart(required = false, value = "attach") MultipartFile file, KefuMessageEntity record) throws IOException {
        if (file != null && !file.isEmpty()) {
            String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
            String filename = DateUtil.getDateString() + "_" + file.getOriginalFilename();
            saveIspFile(filePath, filename, file, IspConstants.FILE_TYPE_KEFU);

            record.setAttachment("kefu/" + filename);
        }
        record.setJiacn(EsContextHolder.getContext().getJiacn());
        record.setClientId(EsContextHolder.getContext().getClientId());
        kefuMessageService.create(record);
        return JsonResult.success(record);
    }

    /**
     * 更新消息信息
     *
     * @param record 消息信息
     * @return 消息信息
     */
    @PreAuthorize("hasAuthority('kefu-message_update')")
    @RequestMapping(value = "/message/update", method = RequestMethod.POST)
    public Object updateMessage(@RequestBody KefuMessageEntity record) {
        kefuMessageService.update(record);
        return JsonResult.success(record);
    }

    /**
     * 删除消息
     *
     * @param id 消息ID
     * @return 处理结果
     */
    @PreAuthorize("hasAuthority('kefu-message_delete')")
    @RequestMapping(value = "/message/delete", method = RequestMethod.GET)
    public Object deleteMessage(@RequestParam(name = "id") Long id) throws Exception {
        KefuMessageEntity record = kefuMessageService.get(id);
        if (record == null || !Objects.equals(EsContextHolder.getContext().getClientId(), record.getClientId())) {
            throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        kefuMessageService.delete(id);
        return JsonResult.success();
    }

    /**
     * 客服内容图片上传
     *
     * @param file 图片文件
     * @return 图片文件信息
     * @throws Exception 异常信息
     */
    @PreAuthorize("hasAuthority('kefu-image_upload')")
    @RequestMapping(value = "/image/upload", method = RequestMethod.POST)
    public Object updateLogo(@RequestPart MultipartFile file) throws Exception {
        String filename = DateUtil.getDateString() + "_" + file.getOriginalFilename();
        String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
        IspFileEntity cf = saveIspFile(filePath, filename, file, EsConstants.FILE_TYPE_AVATAR);

        return JsonResult.success(cf);
    }

    private IspFileEntity saveIspFile(String filePath, String filename, MultipartFile file, Integer fileTypeAvatar) throws IOException {
        File pathFile = new File(filePath + "/kefu");
        //noinspection ResultOfMethodCallIgnored
        pathFile.mkdirs();
        File f = new File(filePath + "/kefu/" + filename);
        file.transferTo(f);

        //保存文件信息
        IspFileEntity cf = new IspFileEntity();
        cf.setClientId(EsContextHolder.getContext().getClientId());
        cf.setExtension(FileUtil.getExtension(filename));
        cf.setName(file.getOriginalFilename());
        cf.setSize(file.getSize());
        cf.setType(fileTypeAvatar);
        cf.setUri("kefu/" + filename);
        fileService.create(cf);
        return cf;
    }
}