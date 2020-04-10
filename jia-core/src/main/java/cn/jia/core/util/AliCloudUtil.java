package cn.jia.core.util;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.alidns.model.v20150109.*;
import com.aliyuncs.profile.DefaultProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 腾讯云签名工具类
 * @author chc
 */
public class AliCloudUtil {

    public static Map<String, Object> dnsSend(String domain, String subDomain, String value, String dnsKey, String dnsToken, String action) throws Exception{
        // 设置鉴权参数，初始化客户端
        DefaultProfile profile = DefaultProfile.getProfile("cn-shenzhen", dnsKey, dnsToken);
        IAcsClient client = new DefaultAcsClient(profile);

        if("RecordList".equals(action)) {
            // 查询指定二级域名的最新解析记录
            DescribeDomainRecordsRequest describeDomainRecordsRequest = new DescribeDomainRecordsRequest();
            // 主域名
            describeDomainRecordsRequest.setDomainName(domain);
            // 主机记录
            describeDomainRecordsRequest.setRRKeyWord(subDomain);
            // 解析记录类型
            describeDomainRecordsRequest.setType("A");
            DescribeDomainRecordsResponse describeDomainRecordsResponse = client.getAcsResponse(describeDomainRecordsRequest);

            List<DescribeDomainRecordsResponse.Record> domainRecords = describeDomainRecordsResponse.getDomainRecords();

            List<Map<String, Object>> records = new ArrayList<>();
            for (DescribeDomainRecordsResponse.Record record : domainRecords) {
                records.add(BeanUtil.convertBean(record));
            }
            Map<String, Object> result = new HashMap<>();
            result.put("records", records);
            return result;
        } else if("RecordCreate".equals(action)) {
            AddDomainRecordRequest addDomainRecordRequest = new AddDomainRecordRequest();
            addDomainRecordRequest.setDomainName(domain);
            addDomainRecordRequest.setRR(subDomain);
            addDomainRecordRequest.setType("A");
            addDomainRecordRequest.setValue(value);
            client.getAcsResponse(addDomainRecordRequest);
        } else if("RecordDelete".equals(action)) {
            DeleteDomainRecordRequest deleteDomainRecordRequest = new DeleteDomainRecordRequest();
            deleteDomainRecordRequest.setRecordId(subDomain);
            client.getAcsResponse(deleteDomainRecordRequest);
        } else if("RecordUpdate".equals(action)){
            // 修改解析记录
            UpdateDomainRecordRequest updateDomainRecordRequest = new UpdateDomainRecordRequest();
            // 主机记录
            updateDomainRecordRequest.setRR(subDomain);
            // 记录ID
            updateDomainRecordRequest.setRecordId(subDomain);
            // 将主机记录值改为当前主机IP
            updateDomainRecordRequest.setValue(value);
            // 解析记录类型
            updateDomainRecordRequest.setType("A");
            client.getAcsResponse(updateDomainRecordRequest);
        }
        return null;
    }
}
