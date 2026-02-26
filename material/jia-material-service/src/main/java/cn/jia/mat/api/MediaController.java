package cn.jia.mat.api;

import cn.jia.base.service.DictService;
import cn.jia.core.common.EsHandler;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.mat.common.MatConstants;
import cn.jia.mat.common.MatErrorConstants;
import cn.jia.mat.entity.MatMediaEntity;
import cn.jia.mat.entity.MatMediaReqVO;
import cn.jia.mat.entity.MatMediaResVO;
import cn.jia.mat.service.MatMediaService;
import cn.jia.mat.vomapper.MatMediaVOMapper;
import com.github.pagehelper.PageInfo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/media")
@Slf4j
public class MediaController {

    @Autowired
    private MatMediaService mediaService;
    @Autowired(required = false)
    private DictService dictService;
    @Value("${mat.web.realpath:/home/isp/hosts/jia/web}")
    private String webRealPath;

    /**
     * 获取媒体信息
     *
     * @param id id
     * @return 媒体信息
     */
    @GetMapping(value = "/get")
    public Object findById(@RequestParam(name = "id") Long id) {
        MatMediaEntity media = mediaService.get(id);
        EsHandler.assertNotNull(media);
        MatMediaResVO mediaVO = MatMediaVOMapper.INSTANCE.toVO(media);
        mediaVO.setLink(dictService.getValue(MatConstants.DICT_TYPE_MODULE_URL,
                MatConstants.MODULE_URL_MATERIAL) + "/" + media.getUrl());
        return JsonResult.success(media);
    }

    /**
     * 获取媒体内容
     *
     * @param id id
     * @return 媒体内容
     */
    @GetMapping(value = "/get/content")
    public Object findContentById(@RequestParam(name = "id") Long id) {
        MatMediaEntity media = mediaService.get(id);
        String content = FileUtil.readString(webRealPath + "/" + media.getUrl());
        EsHandler.assertNotNull(media);
        return JsonResult.success(content);
    }

    /**
     * 创建媒体
     *
     * @param media 媒体信息
     * @return 创建结果
     */
    @PostMapping(value = "/create")
    public Object create(@RequestBody MatMediaEntity media) {
        MatMediaEntity mediaEntity = mediaService.create(media);
        EsHandler.assertNotNull(mediaEntity);
        return JsonResult.success(mediaEntity);
    }

    /**
     * 更新媒体信息
     *
     * @param media 媒体信息
     * @return 更新结果
     */
    @PostMapping(value = "/update")
    public Object update(@RequestBody MatMediaEntity media) {
        mediaService.update(media);
        return JsonResult.success(media);
    }

    /**
     * 删除媒体
     *
     * @param id 媒体ID
     * @return 删除结果
     */
    @GetMapping(value = "/delete")
    public Object delete(@RequestParam(name = "id") Long id) {
        MatMediaEntity media = mediaService.get(id);
        EsHandler.assertNotNull(media);
        File file = new File(webRealPath + "/" + media.getUrl());
        file.delete();
        mediaService.delete(id);
        return JsonResult.success();
    }

    /**
     * 获取所有媒体信息
     *
     * @return 媒体信息
     */
    @PostMapping(value = "/list")
    public Object list(@RequestBody JsonRequestPage<MatMediaReqVO> page) {
        MatMediaReqVO example = Optional.ofNullable(page.getSearch()).orElse(new MatMediaReqVO());
        PageInfo<MatMediaEntity> mediaList = mediaService.findPage(example, page.getPageNum(), page.getPageSize(), page.getOrderBy());
        List<MatMediaResVO> medias =
                mediaList.getList().stream().map(media -> {
                    MatMediaResVO mediaResVO = MatMediaVOMapper.INSTANCE.toVO(media);
                    mediaResVO.setLink(dictService.getValue(MatConstants.DICT_TYPE_MODULE_URL,
                            MatConstants.MODULE_URL_MATERIAL) + "/" + media.getUrl());
                    return mediaResVO;
                }).collect(Collectors.toList());
        JsonResultPage<MatMediaResVO> result = new JsonResultPage<>(medias);
        result.setPageNum(mediaList.getPageNum());
        result.setTotal(mediaList.getTotal());
        return result;
    }

    /**
     * 新闻图片上传，返回全路径
     *
     * @param file 图片文件
     * @return 图片服务器全路径
     */
    @PostMapping(value = "/upload")
    public Object upload(@RequestPart(required = false, value = "file") MultipartFile file, MatMediaResVO media) throws Exception {
        if (media.getType() == null) {
            throw new EsRuntimeException(MatErrorConstants.MEDIA_TYPE_NEED);
        }
        Long now = DateUtil.nowTime();
        String mediaType = dictService.getValue(MatConstants.DICT_TYPE_MEDIA_TYPE, String.valueOf(media.getType()));
        String filePath = webRealPath + "/" + mediaType;
        log.debug(filePath);
        File pathFile = new File(filePath);
        if (!pathFile.exists()) {
            pathFile.mkdirs();
        }
        //如果是文件上传，则直接保存文件
        if (file != null) {
            String fileName = file.getOriginalFilename();
            if (StringUtil.isEmpty(media.getTitle())) {
                media.setTitle(fileName);
            }
            media.setUrl(mediaType + "/" + now + "_" + fileName);

            try {
                file.transferTo(new File(filePath + "/" + now + "_" + fileName));
            } catch (IllegalStateException | IOException e) {
                throw new EsRuntimeException();
            }
        }
        //如果是内容上传，则将内容写到本地文件
        else {
            String fileName = media.getTitle();
            media.setUrl(mediaType + "/" + now + "_" + fileName);
            if (StringUtil.isEmpty(fileName)) {
                throw new EsRuntimeException(MatErrorConstants.MEDIA_TITLE_NEED);
            }
            if (StringUtil.isEmpty(media.getContent())) {
                throw new EsRuntimeException(MatErrorConstants.MEDIA_CONTENT_NEED);
            }
            StreamUtil.io(new ByteArrayInputStream(media.getContent().getBytes()), new FileOutputStream(filePath + "/" + now + "_" + fileName));
        }

        mediaService.create(media);
        media.setLink(dictService.getValue(MatConstants.DICT_TYPE_MODULE_URL, MatConstants.MODULE_URL_MATERIAL) + "/" + media.getUrl());
        return JsonResult.success(media);
    }

    /**
     * 获取媒体缩略图
     *
     * @param id
     * @return
     * @throws IOException
     */
    @RequestMapping(value = "/thumbnail", method = RequestMethod.GET)
    public Object thumbnail(@RequestParam(name = "id", required = true) Long id, HttpServletResponse response) throws IOException {
        MatMediaEntity media = mediaService.get(id);
        String filePath = webRealPath + "/thumbnail/" + media.getUrl();
        File file = new File(filePath);
        String parentPath = file.getParent();
        String fileName = FileUtil.getName(file.getName());
        String extension = FileUtil.getExtension(file.getName());
        file = new File(parentPath + "/" + fileName + ".jpg");
        if (!file.exists()) {
            FileUtil.mkdirs(parentPath);
            if ("html".equalsIgnoreCase(extension) || "htm".equalsIgnoreCase(extension)) {
                String htmlText = FileUtil.readString(webRealPath + "/" + media.getUrl());
//				htmlText = "<div style=\"width:800px;height:600px;overflow:hidden;\">"+htmlText+"</div>";
                ImgUtil.html2Image(htmlText, file.getPath());
                Thumbnails.of(file).sourceRegion(Positions.TOP_LEFT, 750, 500).size(327, 218).keepAspectRatio(false).toFile(file);
//				ImgUtil.compressPic(file.getPath(), file.getPath(), 327, 218, true);
            } else if ("jpg".equalsIgnoreCase(extension) || "jpeg".equalsIgnoreCase(extension) || "gif".equalsIgnoreCase(extension) || "png".equalsIgnoreCase(extension)) {
                Thumbnails.of(webRealPath + "/" + media.getUrl()).size(327, 218).toFile(file.getPath());
            }

        }
        response.setContentType("image/jpg");
        FileInputStream fis = null;
        OutputStream os = null;
        try {
            fis = new FileInputStream(file);
            os = response.getOutputStream();
            int count = 0;
            byte[] buffer = new byte[1024 * 8];
            while ((count = fis.read(buffer)) != -1) {
                os.write(buffer, 0, count);
                os.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            fis.close();
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "ok";
    }
}
