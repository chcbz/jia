package cn.jia.agent.mapper;

/**
 * Internal one-statement projection for an exact-scope scene snapshot.
 * Only public snapshot fields plus the requested scope are projected.
 */
public class AgentSceneSnapshotRow {
    private String scopeTenantId;
    private String scopeClientId;
    private String scopeSceneId;
    private Long sceneVersion;
    private String agentId;
    private String personaCode;
    private String status;
    private String stateAgentId;
    private String statePersonaCode;
    private String behavior;
    private String originRegionId;
    private String targetRegionId;
    private String relatedType;
    private String relatedId;
    private String phase;
    private Long stateVersion;
    private Long startedAt;
    private Long expectedArrivalAt;
    private Long expiresAt;

    public String getScopeTenantId() { return scopeTenantId; }
    public void setScopeTenantId(String scopeTenantId) { this.scopeTenantId = scopeTenantId; }
    public String getScopeClientId() { return scopeClientId; }
    public void setScopeClientId(String scopeClientId) { this.scopeClientId = scopeClientId; }
    public String getScopeSceneId() { return scopeSceneId; }
    public void setScopeSceneId(String scopeSceneId) { this.scopeSceneId = scopeSceneId; }
    public Long getSceneVersion() { return sceneVersion; }
    public void setSceneVersion(Long sceneVersion) { this.sceneVersion = sceneVersion; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getPersonaCode() { return personaCode; }
    public void setPersonaCode(String personaCode) { this.personaCode = personaCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStateAgentId() { return stateAgentId; }
    public void setStateAgentId(String stateAgentId) { this.stateAgentId = stateAgentId; }
    public String getStatePersonaCode() { return statePersonaCode; }
    public void setStatePersonaCode(String statePersonaCode) { this.statePersonaCode = statePersonaCode; }
    public String getBehavior() { return behavior; }
    public void setBehavior(String behavior) { this.behavior = behavior; }
    public String getOriginRegionId() { return originRegionId; }
    public void setOriginRegionId(String originRegionId) { this.originRegionId = originRegionId; }
    public String getTargetRegionId() { return targetRegionId; }
    public void setTargetRegionId(String targetRegionId) { this.targetRegionId = targetRegionId; }
    public String getRelatedType() { return relatedType; }
    public void setRelatedType(String relatedType) { this.relatedType = relatedType; }
    public String getRelatedId() { return relatedId; }
    public void setRelatedId(String relatedId) { this.relatedId = relatedId; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public Long getStateVersion() { return stateVersion; }
    public void setStateVersion(Long stateVersion) { this.stateVersion = stateVersion; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
    public Long getExpectedArrivalAt() { return expectedArrivalAt; }
    public void setExpectedArrivalAt(Long expectedArrivalAt) { this.expectedArrivalAt = expectedArrivalAt; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
}
