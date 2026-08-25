package cn.jia.agent.dao.impl;

import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.dao.AgentHostedProfileDao;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.mapper.AgentHostedProfileMapper;
import cn.jia.common.dao.BaseDaoImpl;
import jakarta.inject.Named;

@Named
public class AgentHostedProfileDaoImpl extends BaseDaoImpl<AgentHostedProfileMapper, AgentHostedProfileEntity>
        implements AgentHostedProfileDao {
    @Override public AgentHostedProfileEntity findExact(String t, String c, String o, long b) {
        requireScope(t, c, o, b);
        return baseMapper.findExact(t, c, o, b);
    }
    @Override public AgentHostedProfileEntity findExactForUpdate(String t, String c, String o, long b) {
        requireScope(t, c, o, b);
        return baseMapper.findExactForUpdate(t, c, o, b);
    }
    @Override public int transition(long id, String es, long eg, String ns, long ng, boolean de) {
        if (id <= 0 || eg < 0 || ng < 0 || !AgentHostedProfileState.VALUES.contains(es)
                || !AgentHostedProfileState.VALUES.contains(ns)) {
            throw new IllegalArgumentException("hosted transition identity is invalid");
        }
        return baseMapper.transition(id, es, eg, ns, ng, de, System.currentTimeMillis());
    }
    @Override public int markRepair(long id, String expectedState, String expectedResumeState,
            long expectedGeneration, String resumeState, String errorCategory) {
        requireDurableCheckpoint(id, expectedState, expectedResumeState, expectedGeneration);
        if (!AgentHostedProfileState.VALUES.contains(resumeState)
                || AgentHostedProfileState.REPAIR_REQUIRED.equals(resumeState)) {
            throw new IllegalArgumentException("hosted repair checkpoint is invalid");
        }
        String category = errorCategory == null ? "HOSTED_PROFILE_FAILURE:RuntimeException" : errorCategory;
        if (!category.matches("[A-Z0-9_]+:[A-Za-z0-9_$]{1,160}")) {
            throw new IllegalArgumentException("hosted repair error category is invalid");
        }
        return baseMapper.markRepair(id, expectedState, expectedResumeState, expectedGeneration,
                resumeState, category, System.currentTimeMillis());
    }

    @Override public int resumeRepair(long id, String resumeState, long expectedGeneration) {
        if (id <= 0 || expectedGeneration < 0 || !AgentHostedProfileState.VALUES.contains(resumeState)
                || AgentHostedProfileState.REPAIR_REQUIRED.equals(resumeState)) {
            throw new IllegalArgumentException("hosted repair resume checkpoint is invalid");
        }
        return baseMapper.resumeRepair(id, resumeState, expectedGeneration, System.currentTimeMillis());
    }

    private void requireDurableCheckpoint(long id, String expectedState,
            String expectedResumeState, long expectedGeneration) {
        if (id <= 0 || expectedGeneration < 0 || !AgentHostedProfileState.VALUES.contains(expectedState)
                || (expectedResumeState != null
                    && !AgentHostedProfileState.VALUES.contains(expectedResumeState))) {
            throw new IllegalArgumentException("hosted expected checkpoint is invalid");
        }
    }

    private void requireScope(String tenantId, String clientId, String ownerJiacn, long bindingId) {
        if (bindingId <= 0) throw new IllegalArgumentException("bindingId is invalid");
        requireExact(tenantId, "tenantId");
        requireExact(clientId, "clientId");
        requireExact(ownerJiacn, "ownerJiacn");
        if ("0".equals(ownerJiacn) || !tenantId.equals(ownerJiacn)) {
            throw new IllegalArgumentException("hosted owner scope is invalid");
        }
    }

    private void requireExact(String value, String field) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 50
                || hasUnpairedSurrogate(value)
                || isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().noneMatch(codePoint -> !isPadding(codePoint))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }

    private boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
