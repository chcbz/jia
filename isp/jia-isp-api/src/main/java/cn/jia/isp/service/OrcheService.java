package cn.jia.isp.service;

import cn.jia.isp.entity.OrcheEntity;

import java.util.List;
import java.util.Map;

public interface OrcheService {
    /**
     * 执行编排
     *
     * @param data 数据
     * @param orcheMeta 编排元数据
     * @return 结果
     */
    List<Map<String, Object>> executeOrche(Map<String, List<Map<String, Object>>> data, OrcheEntity orcheMeta);
}
