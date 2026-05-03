package cn.jia.chat.memory;

import lombok.Data;

import java.util.List;

/**
 * <p>
 * 记忆文档实体
 * </p>
 *
 * @author chc
 * @since 2026-05-01
 */
@Data
public class MemoryDocument {

    /**
     * 文档ID (ES auto-generate)
     */
    private String id;

    /**
     * 用户标识
     */
    private String jiacn;

    /**
     * 会话ID (会话级摘要时使用)
     */
    private String conversationId;

    /**
     * 记忆内容/摘要
     */
    private String content;

    /**
     * 摘要类型: conversation / daily_summary / weekly_summary / monthly_summary
     */
    private String summaryType;

    /**
     * 话题标签
     */
    private String topic;

    /**
     * 关键点列表
     */
    private List<String> keyPoints;

    /**
     * 分类标签
     */
    private List<String> categories;

    /**
     * 用户偏好
     */
    private List<String> preferences;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 周期开始时间
     */
    private Long periodStart;

    /**
     * 周期结束时间
     */
    private Long periodEnd;

    /**
     * 来源会话数量
     */
    private Integer conversationCount;

    /**
     * 向量嵌入 (用于相似度检索)
     */
    private float[] embedding;

    /**
     * 相似度分数 (检索时使用)
     */
    private transient double score;
}
