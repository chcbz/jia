package cn.jia.core.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

@Slf4j
public class ElasticsearchService {
    @Autowired
    private ElasticsearchClient elasticsearchClient;

    /**
     * 查询最大匹配值（match 查询）
     *
     * @param index 索引名
     * @param field 字段名
     * @param value 查询内容
     * @param clazz 结果类型
     * @return 搜索结果列表（仅返回 source 转换后的对象）
     */
    public <T> SearchResponse<T> searchMatch(String index, String field, String value, Class<T> clazz) {
        try {
            return elasticsearchClient.search(s -> s
                            .index(index)
                            .query(q -> q
                                    .match(m -> m
                                            .field(field)
                                            .query(value)
                                    )
                            ),
                    clazz
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 删除索引中的某个文档
     *
     * @param id    文档ID
     * @param index 索引名
     * @return 被删除的文档ID（若成功）
     */
    public String delete(String id, String index) {
        try {
            DeleteResponse response = elasticsearchClient.delete(d -> d
                    .index(index)
                    .id(id)
            );
            return response.id();
        } catch (IOException e) {
            log.error("Elasticsearch delete failed", e);
            throw new RuntimeException("Delete failed", e);
        }
    }
}
