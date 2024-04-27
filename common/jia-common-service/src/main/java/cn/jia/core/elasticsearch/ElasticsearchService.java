package cn.jia.core.elasticsearch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.Queries;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

@Slf4j
public class ElasticsearchService {

    @Autowired
    ElasticsearchOperations elasticsearchOperations;

    /**
     * 查询最大匹配值
     *
     * @param index 索引
     * @param field 查询标题
     * @param value 查询内容
     * @return 查询结果
     */
    public <T>  SearchHits<T> searchMatch(String index, String field, String value, Class<T> clazz) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(Queries.matchQueryAsQuery(field, value, null, null)).build();
        return elasticsearchOperations.search(query, clazz, IndexCoordinates.of(index));
    }

    /**
     * 删除索引中的某个id
     *
     * @param id id
     * @param index 索引
     * @return 被删除的id
     */
    public String delete(String id, String index) {
        return elasticsearchOperations.delete(id, IndexCoordinates.of(index));
    }
}
