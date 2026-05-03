package cn.jia.chat.memory;

import java.util.List;

/**
 * <p>
 * 记忆存储库接口
 * </p>
 *
 * @author chc
 * @since 2026-05-01
 */
public interface MemoryRepository {

    /**
     * 保存记忆文档
     *
     * @param document 记忆文档
     */
    void save(MemoryDocument document);

    /**
     * 保存记忆文档列表
     *
     * @param documents 记忆文档列表
     */
    void saveAll(List<MemoryDocument> documents);

    /**
     * 搜索记忆 (带会话权重)
     *
     * @param jiacn 用户标识
     * @param userMessage 查询内容
     * @param currentConversationId 当前会话ID
     * @param topK 返回数量
     * @param similarityThreshold 相似度阈值，低于此值的结果会被过滤
     * @return 记忆文档列表
     */
    List<MemoryDocument> searchWithConversationBoost(String jiacn, String userMessage, String currentConversationId, int topK, double similarityThreshold);

    /**
     * 搜索记忆 (简单向量检索)
     *
     * @param jiacn 用户标识
     * @param queryVector 查询向量
     * @param topK 返回数量
     * @return 记忆文档列表
     */
    List<MemoryDocument> search(String jiacn, float[] queryVector, int topK);

    /**
     * 根据用户查询会话摘要
     *
     * @param jiacn 用户标识
     * @param sinceTime 起始时间
     * @return 摘要内容列表
     */
    List<String> findConversationSummariesByJiacn(String jiacn, long sinceTime);

    /**
     * 根据用户查询日汇总
     *
     * @param jiacn 用户标识
     * @param sinceTime 起始时间
     * @return 摘要内容列表
     */
    List<String> findDailySummariesByJiacn(String jiacn, long sinceTime);

    /**
     * 根据用户查询周汇总
     *
     * @param jiacn 用户标识
     * @param sinceTime 起始时间
     * @return 摘要内容列表
     */
    List<String> findWeeklySummariesByJiacn(String jiacn, long sinceTime);

    /**
     * 删除过期文档
     *
     * @param beforeTime 时间阈值
     */
    void deleteExpiredDocuments(long beforeTime);

    /**
     * 删除指定类型的旧文档 (保留最新 N 条)
     *
     * @param summaryType 摘要类型
     * @param keepCount 保留数量
     */
    void deleteOldDocuments(String summaryType, int keepCount);

    /**
     * 获取用户的记忆文档总数
     *
     * @param jiacn 用户标识
     * @return 文档数量
     */
    long countByJiacn(String jiacn);

    /**
     * 生成文本嵌入向量
     * 使用 Spring AI EmbeddingModel 生成真实的嵌入向量
     *
     * @param text 文本内容
     * @return 嵌入向量
     */
    float[] generateEmbedding(String text);
}
