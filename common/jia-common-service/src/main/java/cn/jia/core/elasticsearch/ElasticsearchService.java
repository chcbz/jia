package cn.jia.core.elasticsearch;

import cn.jia.core.deadline.SafeRequestTimeoutException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

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
            throw classifySafeReadFailure(e);
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
            logDeleteFailure(e);
            // Preserve the existing delete contract: after a transport failure the caller cannot infer whether the
            // write reached Elasticsearch, so this method does not claim that retrying is safe.
            throw new RuntimeException("Delete failed", e);
        }
    }

    private RuntimeException classifySafeReadFailure(IOException exception) {
        if (hasCause(exception, SocketTimeoutException.class) || hasCauseWithSimpleNameSuffix(exception, "TimeoutException")) {
            return SafeRequestTimeoutException.dependencyTimedOutSafely(
                    SafeRequestTimeoutException.Dependency.ELASTICSEARCH);
        }
        if (hasCause(exception, ConnectException.class) || hasCause(exception, NoRouteToHostException.class)
                || hasCause(exception, UnknownHostException.class) || hasCauseWithSimpleNameSuffix(exception, "NoHttpResponseException")) {
            return SafeRequestTimeoutException.dependencyUnavailableBeforeWork(
                    SafeRequestTimeoutException.Dependency.ELASTICSEARCH);
        }
        return new RuntimeException(exception);
    }

    private void logDeleteFailure(IOException exception) {
        if (hasCause(exception, SocketTimeoutException.class) || hasCauseWithSimpleNameSuffix(exception, "TimeoutException")) {
            log.warn("Elasticsearch delete timed out; outcome may be unknown");
            return;
        }
        if (hasCause(exception, ConnectException.class) || hasCause(exception, NoRouteToHostException.class)
                || hasCause(exception, UnknownHostException.class) || hasCauseWithSimpleNameSuffix(exception, "NoHttpResponseException")) {
            log.warn("Elasticsearch delete was unavailable before a response; outcome may be unknown");
            return;
        }
        log.error("Elasticsearch delete failed", exception);
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasCauseWithSimpleNameSuffix(Throwable exception, String suffix) {
        Throwable current = exception;
        while (current != null) {
            if (current.getClass().getSimpleName().endsWith(suffix)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
