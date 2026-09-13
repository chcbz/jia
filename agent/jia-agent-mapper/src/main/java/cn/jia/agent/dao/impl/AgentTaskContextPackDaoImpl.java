package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskContextPackDao;
import cn.jia.agent.entity.AgentTaskContextPackTaskSourceRow;
import cn.jia.agent.mapper.AgentTaskContextPackMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Objects;

@Named
public class AgentTaskContextPackDaoImpl implements AgentTaskContextPackDao {
    private final AgentTaskContextPackMapper mapper;

    @Inject
    public AgentTaskContextPackDaoImpl(AgentTaskContextPackMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public AgentTaskContextPackTaskSourceRow findTaskDescription(
            String tenantId, String clientId, String taskId) {
        requireId(tenantId, "tenantId", 50);
        requireId(clientId, "clientId", 50);
        requireId(taskId, "taskId", 100);
        return mapper.findTaskDescription(tenantId, clientId, taskId);
    }

    private static void requireId(String value, String field, int maxCodePoints) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.codePointCount(0, value.length()) > maxCodePoints
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
