package cn.jia.agent.dao;

import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceOperationEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;

import java.util.List;

/** Strict owner-scoped persistence boundary for personal uploads. */
public interface PersonalWorkspaceDao {
    PersonalWorkspaceFileEntity findFile(String tenantId, String clientId, String ownerJiacn, String fileId);
    PersonalWorkspaceFileEntity lockFile(String tenantId, String clientId, String ownerJiacn, String fileId);
    List<PersonalWorkspaceFileEntity> listFiles(String tenantId, String clientId, String ownerJiacn,
            String q, String mediaFamily, String state, Long beforeCreatedAt, String afterFileId, int limit);
    void insertFile(PersonalWorkspaceFileEntity entity);
    void updateFile(PersonalWorkspaceFileEntity entity);
    PersonalWorkspaceVersionEntity findVersion(String tenantId, String clientId, String ownerJiacn,
            String fileId, int version);
    List<PersonalWorkspaceVersionEntity> listVersions(String tenantId, String clientId, String ownerJiacn,
            String fileId, int limit);
    void insertVersion(PersonalWorkspaceVersionEntity entity);
    PersonalWorkspaceOperationEntity findOperationByIdempotency(String tenantId, String clientId,
            String ownerJiacn, String type, String key);
    PersonalWorkspaceOperationEntity findOperation(String tenantId, String clientId,
            String ownerJiacn, String operationId);
    void insertOperation(PersonalWorkspaceOperationEntity entity);
    void updateOperation(PersonalWorkspaceOperationEntity entity);
}
