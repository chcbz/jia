package cn.jia.user.dao;

/**
 * One row from the bounded user-list relation query.
 */
public record UserRelationRow(Long userId, String relationType, Long relationId) {
}
