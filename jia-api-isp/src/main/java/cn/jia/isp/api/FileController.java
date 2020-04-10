package cn.jia.isp.api;

import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.isp.entity.IspFile;
import cn.jia.isp.service.FileService;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
		response.setCharacterEncoding(request.getCharacterEncoding());
		IspFile record = fileService.findByUri(uri);
		if(record != null) {
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
