package cn.jia.isp.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.FileUtil;
import cn.jia.core.util.JSONUtil;
import cn.jia.isp.entity.IspFile;
import cn.jia.isp.service.FileService;
import com.github.pagehelper.Page;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.HtmlUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileInputStream;
import java.net.URLDecoder;

@RestController
@RequestMapping("/file")
public class FileController {
	
	@Autowired
	private FileService fileService;
	
	/**
	 * 获取资源信息
	 * @param id 资源ID
	 * @return 资源信息
	 */
	@PreAuthorize("hasAuthority('file-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Integer id) {
		IspFile file = fileService.find(id);
		return JSONResult.success(file);
	}

    /**
     * 获取资源列表
     * @param page 查询条件
     * @return 资源列表
     */
	@PreAuthorize("hasAuthority('file-list')")
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public Object list(@RequestBody JSONRequestPage<String> page) {
		IspFile example = JSONUtil.fromJson(page.getSearch(), IspFile.class);
        Page<IspFile> fileList = fileService.list(example, page.getPageNum(), page.getPageSize());
        JSONResultPage<Object> result = new JSONResultPage<>(fileList.getResult());
        result.setPageNum(fileList.getPageNum());
        result.setTotal(fileList.getTotal());
        return result;
    }

	/**
	 * 删除资源
	 * @param id 资源ID
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('file-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Integer id) throws Exception {
		IspFile file = fileService.find(id);
	    if(file == null){
	        throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
        }
        String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
        java.io.File f = new java.io.File(filePath + "/" + file.getUri());
        if(f.exists() && !f.delete()){
            throw new EsRuntimeException();
        }

		fileService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 根据路径获取资源信息
	 */
	@RequestMapping(value = "/res/{uri:.+}/**", method = RequestMethod.GET)
	@ResponseBody
	public byte[] findByURI(@PathVariable String uri, HttpServletRequest request, HttpServletResponse response) throws Exception {
		final String path = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE).toString();
		final String bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE).toString();
		String arguments = new AntPathMatcher().extractPathWithinPattern(bestMatchingPattern, path);

		if (null != arguments && !arguments.isEmpty()) {
			uri = uri + '/' + arguments;
		}
		uri = HtmlUtils.htmlUnescape(URLDecoder.decode(uri, "UTF-8"));
		String extension = FileUtil.getExtension(uri);
		if("html".equalsIgnoreCase(extension) || "htm".equalsIgnoreCase(extension)) {
			response.setContentType("text/html");
            response.setCharacterEncoding(request.getCharacterEncoding());
		} else if("jpg".equalsIgnoreCase(extension) || "jpeg".equalsIgnoreCase(extension) || "gif".equalsIgnoreCase(extension) || "png".equalsIgnoreCase(extension)) {
			response.setContentType("image/" + extension);
		}
		IspFile record = fileService.findByUri(uri);
		if(record != null && record.getClientId().equals(EsSecurityHandler.clientId())) {
			String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
			java.io.File file = new java.io.File(filePath + "/" + uri);
			FileInputStream fis = new FileInputStream(file);
			byte[] b = IOUtils.toByteArray(fis);
			fis.close();
			return b;
		}
		return null;
	}

}
