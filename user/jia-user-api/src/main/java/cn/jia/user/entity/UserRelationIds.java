package cn.jia.user.entity;

import java.util.List;

/**
 * Relation identifiers attached to one user-list row.
 */
public record UserRelationIds(List<Long> roleIds, List<Long> orgIds, List<Long> groupIds) {
    public UserRelationIds {
        roleIds = List.copyOf(roleIds);
        orgIds = List.copyOf(orgIds);
        groupIds = List.copyOf(groupIds);
    }
}
