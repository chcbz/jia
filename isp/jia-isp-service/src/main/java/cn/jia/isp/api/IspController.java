package cn.jia.isp.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.*;
import cn.jia.isp.common.IspConstants;
import cn.jia.isp.common.IspErrorConstants;
import cn.jia.isp.entity.*;
import cn.jia.isp.service.DnsService;
import cn.jia.isp.service.IspService;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ISP服务接口
 *
 * @author chc
 * @since 2017年12月8日 下午3:31:20
 */
@RestController
@RequestMapping("/isp")
public class IspController {

    @Inject
    private IspService ispService;
    @Inject
    private DnsService dnsService;

    /**
     * 服务器列表
     *
     * @param page 分页请求对象
     * @param request HTTP请求对象
     * @return 服务器列表信息
     */
    @RequestMapping(value = "/server/list", method = RequestMethod.POST)
    public Object listServer(@RequestBody JsonRequestPage<IspServerEntity> page, HttpServletRequest request) {
        String clientId = EsSecurityHandler.checkClientId(request);
        IspServerEntity example = Optional.ofNullable(page.getSearch()).orElse(new IspServerEntity());
        example.setClientId(clientId);
        PageInfo<IspServerEntity> ispList = ispService.listServer(example, page.getPageNum(), page.getPageSize(), page.getOrderBy());
        JsonResultPage<IspServerEntity> result = new JsonResultPage<>(ispList.getList());
        result.setPageNum(ispList.getPageNum());
        result.setTotal(ispList.getTotal());
        return result;
    }

    /**
     * 获取服务器信息
     *
     * @param id 服务器ID
     * @return 服务器信息
     */
    @RequestMapping(value = "/server/get", method = RequestMethod.GET)
    public Object findServerById(@RequestParam(name = "id") Long id) throws Exception {
        IspServerEntity record = ispService.findServer(id);
        if (record == null) {
            throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
        }
        return JsonResult.success(record);
    }

    /**
     * 创建服务器
     *
     * @param record 服务器实体对象
     * @return 创建结果
     */
    @RequestMapping(value = "/server/create", method = RequestMethod.POST)
    public Object createServer(@RequestBody IspServerEntity record) {
        record.setClientId(EsContextHolder.getContext().getClientId());
        ispService.createServer(record);
        return JsonResult.success();
    }

    /**
     * 更新服务器信息
     *
     * @param record 服务器实体对象
     * @return 更新结果
     */
    @RequestMapping(value = "/server/update", method = RequestMethod.POST)
    public Object updateServer(@RequestBody IspServerEntity record) throws Exception {
        ispService.updateServer(record);
        return JsonResult.success(record);
    }

    /**
     * 删除服务器
     *
     * @param id 服务器ID
     * @return 删除结果
     */
    @RequestMapping(value = "/server/delete", method = RequestMethod.GET)
    public Object deleteServer(@RequestParam(name = "id") Long id) {
        ispService.deleteServer(id);
        return JsonResult.success();
    }

    /**
     * 刷新HTTPS证书
     *
     * @param id 服务器ID
     * @param domain 域名
     * @return 刷新结果
     */
    @RequestMapping(value = "/server/ssl/refresh", method = RequestMethod.GET)
    public Object refreshSSL(@RequestParam Long id, @RequestParam String domain) {

        return JsonResult.success();
    }

    @RequestMapping(value = "/domain/record/delete", method = RequestMethod.GET)
    public Object refreshSSL(@RequestParam String name, @RequestParam String domain) {

        return JsonResult.success();
    }

    /**
     * 获取DNS对应的hosts信息
     *
     * @param dnsRecordDTO DNS记录DTO对象
     * @return hosts信息
     */
    @RequestMapping(value = "/dns/hosts", method = RequestMethod.GET, produces = "text/plain;charset=UTF-8")
    public Object hosts(DnsRecordDTO dnsRecordDTO) {
        IspDnsZoneEntity zone = new IspDnsZoneEntity();
        zone.setZone(dnsRecordDTO.getZone());
        zone.setKeyName(dnsRecordDTO.getKeyName());
        List<IspDnsZoneEntity> zoneList = dnsService.listDnsZone(zone);
        if (CollectionUtil.isNullOrEmpty(zoneList)) {
            return "no this zone";
        }
        List<IspDnsRecordEntity> recordList = dnsService.listDnsRecord(zoneList.get(0).getId());
        StringBuilder sb = new StringBuilder("#DNS Server");
        for (IspDnsRecordEntity d : recordList) {
            sb.append(System.lineSeparator()).append(d.getIp()).append(" ").append(d.getDomain());
        }

        return sb.toString();
    }

    /**
     * 更新DNS记录信息
     *
     * @param name 服务器名称
     * @param request HTTP请求对象
     * @return 更新结果
     * @throws Exception 更新过程中可能抛出的异常
     */
    @SuppressWarnings("unchecked")
    @RequestMapping("/dns/update_record")
    public Object updateDnsRecord(@RequestParam String name, HttpServletRequest request) throws Exception {
        IspServerEntity ispServer = new IspServerEntity();
        ispServer.setServerName(name);
        ispServer.setClientId(EsSecurityHandler.clientId(request));
        PageInfo<IspServerEntity> list = ispService.listServer(ispServer, 1, 1, null);
        if (list.getSize() == 0) {
            throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
        }
        ispServer = list.getList().get(0);
        ispServer.setIp(HttpUtil.getIpAddr(request));
        ispService.updateServer(ispServer);

        IspDomainEntity ispDomain = new IspDomainEntity();
        ispDomain.setServerId(ispServer.getId());
        PageInfo<IspDomainEntity> domainList = ispService.listDomain(ispDomain, 1, Integer.MAX_VALUE, null);
        for (IspDomainEntity record : domainList.getList()) {
            String[] domainSplit = record.getDomainName().split("\\.");
            String domainName = domainSplit[domainSplit.length - 2] + "." + domainSplit[domainSplit.length - 1];
            String subDomainName = domainSplit.length > 2 ?
                    record.getDomainName().substring(0, record.getDomainName().lastIndexOf(domainName) - 1) : "";
            String ownDomainName = StringUtil.isEmpty(subDomainName) ? "@" : subDomainName;
            String allDomainName = StringUtil.isEmpty(subDomainName) ? "*" : "*." + subDomainName;

            if (IspConstants.DNS_TYPE_TXY.equals(record.getDnsType())) {
                Map<String, Object> data = (Map<String, Object>) TxCloudUtil.dnsSend(
                                domainName, "", "", record.getDnsKey(), record.getDnsToken(), "RecordList")
                        .get("data");
                List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");

                if (CollectionUtil.isNotNullOrEmpty(records)) {
                    for (Map<String, Object> o : records) {
                        if (ownDomainName.equals(o.get("name")) || allDomainName.equals(o.get("name"))) {
                            TxCloudUtil.dnsSend(domainName, String.valueOf(o.get("id")), "",
                                    record.getDnsKey(), record.getDnsToken(), "RecordDelete");
                        }
                    }
                }
                TxCloudUtil.dnsSend(domainName, allDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
                TxCloudUtil.dnsSend(domainName, ownDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
            } else if (IspConstants.DNS_TYPE_ALY.equals(record.getDnsType())) {
                Map<String, Object> data = AliCloudUtil.dnsSend(domainName, "", "", record.getDnsKey(), record.getDnsToken(), "RecordList");
                List<Map<String, Object>> records = (List<Map<String, Object>>) Objects.requireNonNull(data).get("records");

                if (CollectionUtil.isNotNullOrEmpty(records)) {
                    for (Map<String, Object> o : records) {
                        if (ownDomainName.equals(o.get("rR")) || allDomainName.equals(o.get("rR"))) {
                            AliCloudUtil.dnsSend(domainName, String.valueOf(o.get("recordId")), "", record.getDnsKey(), record.getDnsToken(), "RecordDelete");
                        }
                    }
                }
                AliCloudUtil.dnsSend(domainName, allDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
                AliCloudUtil.dnsSend(domainName, ownDomainName, ispServer.getIp(), record.getDnsKey(), record.getDnsToken(), "RecordCreate");
            }
        }

        return JsonResult.success(request, "isp.dns.update.success");
    }
}
