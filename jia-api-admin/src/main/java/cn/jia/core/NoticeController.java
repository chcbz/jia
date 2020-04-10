package cn.jia.core;

import cn.jia.core.common.EsConstants;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.entity.Notice;
import cn.jia.core.service.NoticeService;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.FileUtil;
import cn.jia.core.util.JSONUtil;
import cn.jia.isp.entity.IspFile;
import cn.jia.isp.service.FileService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@RestController
@RequestMapping("/notice")
public class NoticeController {
	
	@Autowired
	private NoticeService noticeService;
	@Autowired
	private FileService fileService;

	/**
	 * 获取公告信息
	 * @param id 公告ID
	 * @return 公告信息
	 */
	/*@PreAuthorize("hasAuthority('notice-get')")*/
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Integer id) {
		Notice notice = noticeService.find(id);
		return JSONResult.success(notice);
	}
	
	/**
	 * 创建公告
	 * @param notice 公告信息
	 * @return 公告信息
	 */
	@PreAuthorize("hasAuthority('notice-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody Notice notice) {
		noticeService.create(notice);
		return JSONResult.success();
	}

	/**
	 * 更新公告信息
	 * @param notice 公告信息
	 * @return 公告信息
	 */
	@PreAuthorize("hasAuthority('notice-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody Notice notice) {
		noticeService.update(notice);
		return JSONResult.success();
	}

	/**
	 * 删除公告
	 * @param id 公告ID
	 * @return 公告信息
	 */
	@PreAuthorize("hasAuthority('notice-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Integer id) {
		noticeService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有公告信息
	 * @return 公告信息
	 */
	/*@PreAuthorize("hasAuthority('notice-list')")*/
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Notice notice = JSONUtil.fromJson(page.getSearch(), Notice.class);
		Page<Notice> noticeList = noticeService.findByExample(notice, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(noticeList.getResult());
		result.setPageNum(noticeList.getPageNum());
		result.setTotal(noticeList.getTotal());
		return result;
	}

	/**
	 * 上传公告内容图片
	 * @param file 图片文件
	 * @return 文件内容
	 * @throws Exception 异常信息
	 */
	@PreAuthorize("hasAuthority('notice-image_upload')")
	@RequestMapping(value = "/image/upload", method = RequestMethod.POST)
	public Object updateLogo(@RequestPart MultipartFile file) throws Exception {
		String filename = DateUtil.getDateString() + "_" + file.getOriginalFilename();
		String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
		File pathFile = new File(filePath + "/notice");
		//noinspection ResultOfMethodCallIgnored
		pathFile.mkdirs();
		File f = new File(filePath + "/notice/" + filename);
		file.transferTo(f);

		//保存文件信息
		IspFile cf = new IspFile();
		cf.setClientId(EsSecurityHandler.clientId());
		cf.setExtension(FileUtil.getExtension(filename));
		cf.setName(file.getOriginalFilename());
		cf.setSize(file.getSize());
		cf.setType(EsConstants.FILE_TYPE_AVATAR);
		cf.setUri("notice/" + filename);
		fileService.create(cf);

		return JSONResult.success(cf);
	}
}
