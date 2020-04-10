package cn.jia.isp.api;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.StreamUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.isp.entity.DnsRecord;
import cn.jia.isp.entity.DnsRecordDTO;
import cn.jia.isp.entity.DnsZone;
import cn.jia.isp.service.DnsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.List;

/**
 * 动态DNS操作接口
 * @author chc
 * @date 2017年12月8日 下午3:31:20
 */
@RestController
@RequestMapping("/dns")
public class DnsController {
//	private final static Logger logger = LoggerFactory.getLogger(DnsController.class);
	
	@Autowired
	private DnsService dnsService;
	@Value("${dns.nsupdate.path}")
	private String nsupdatePath;
	
	/**
	 * 更新DNS记录信息
	 * @param dnsRecordDTO
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('dns-update_record')")
	@RequestMapping("/update_record")
	public Object updateDnsRecord(DnsRecordDTO dnsRecordDTO, HttpServletRequest request) throws Exception {
		dnsRecordDTO.setIp(HttpUtil.getIpAddr(request));
		dnsService.updateDnsRecord(dnsRecordDTO);
		
		//如果IP地址有变化，执行nsupdate命令刷新DNS服务器
		if(!StringUtils.equals(dnsRecordDTO.getIp(),dnsRecordDTO.getHistoryIp())){
			StringBuffer sb = new StringBuffer();
			sb.append("server 127.0.0.1 53");
			sb.append("\r\nzone "+dnsRecordDTO.getZone());
			sb.append("\r\nkey "+dnsRecordDTO.getKeyName()+" "+dnsRecordDTO.getKeyValue());
			sb.append("\r\nupdate delete "+dnsRecordDTO.getDomain());
			sb.append("\r\nupdate add "+dnsRecordDTO.getDomain()+" "+dnsRecordDTO.getTtl()+" "+dnsRecordDTO.getType()+" "+dnsRecordDTO.getIp());
			sb.append("\r\nsend");
			InputStream in = new ByteArrayInputStream(sb.toString().getBytes()); 
			String path = request.getSession().getServletContext().getRealPath("/temp");
			File pathFile = new File(path);
			if(!pathFile.exists()){
				pathFile.mkdirs();
			}
			File file = new File(path+"/"+dnsRecordDTO.getDomain()+".dns");
			FileOutputStream out = new FileOutputStream(file);
			StreamUtil.io(in, out);
			out.flush();
			out.close();
			in.close();
			Runtime.getRuntime().exec(nsupdatePath + " " + file.getCanonicalPath());
		}
		return JSONResult.success(request, "isp.dns.update.success");
	}
	
	/**
	 * 获取DNS对应的hosts信息
	 * @param dnsRecordDTO
	 * @return
	 * @throws IOException
	 */
	@PreAuthorize("hasAuthority('dns-hosts')")
	@RequestMapping(value = "/hosts", method = RequestMethod.GET, produces="text/plain;charset=UTF-8")
	public Object hosts(DnsRecordDTO dnsRecordDTO) throws IOException {
		DnsZone zone = new DnsZone();
		zone.setZone(dnsRecordDTO.getZone());
		zone.setKeyName(dnsRecordDTO.getKeyName());
		List<DnsZone> zoneList = dnsService.listDnsZone(zone);
		if(zoneList == null || zoneList.size() == 0) {
			return "no this zone";
		}
		List<DnsRecord> recordList = dnsService.listDnsRecord(zoneList.get(0).getId());
		StringBuffer sb = new StringBuffer("#DNS Server");
		for(DnsRecord d : recordList) {
			sb.append(System.lineSeparator() + d.getIp() + " " + d.getDomain());
		}
		
		return sb.toString();
	}

}
