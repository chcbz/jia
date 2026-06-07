package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AbilityComparisonDao;
import cn.jia.agent.dao.AbilityEvaluationDao;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AbilityCompareRequestDTO;
import cn.jia.agent.entity.AbilityCompareResultDTO;
import cn.jia.agent.entity.AbilityComparisonEntity;
import cn.jia.agent.entity.AbilityEvaluationDTO;
import cn.jia.agent.entity.AbilityEvaluationEntity;
import cn.jia.agent.entity.AbilityEvaluationRequestDTO;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AbilityEvaluationServiceImpl implements AbilityEvaluationService {
    private final AbilityEvaluationDao abilityEvaluationDao;
    private final AbilityComparisonDao abilityComparisonDao;
    private final AgentPersonaDao agentPersonaDao;
    private final AgentTaskMetaDao agentTaskMetaDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AbilityEvaluationDTO evaluate(AbilityEvaluationRequestDTO request) {
        require(!StringUtil.isBlank(request.getAgentName()), "agentName is required");
        AbilityEvaluationEntity entity = buildEvaluation(request);
        abilityEvaluationDao.insert(entity);
        return toDTO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AbilityCompareResultDTO compare(AbilityCompareRequestDTO request) {
        require(request.getAgentNames() != null && !request.getAgentNames().isEmpty(), "agentNames is required");
        List<AbilityEvaluationDTO> ranking = new ArrayList<>();
        for (String agentName : request.getAgentNames()) {
            AbilityEvaluationRequestDTO evalRequest = new AbilityEvaluationRequestDTO();
            evalRequest.setAgentName(agentName);
            evalRequest.setEvaluationType("COMPARISON");
            evalRequest.setTaskContext(request.getTaskContext());
            evalRequest.setRequiredAbilities(request.getRequiredAbilities());
            ranking.add(evaluate(evalRequest));
        }
        ranking.sort(Comparator.comparing(AbilityEvaluationDTO::getOverallScore, Comparator.nullsLast(Integer::compareTo))
                .reversed());

        AbilityCompareResultDTO result = new AbilityCompareResultDTO();
        result.setRanking(ranking);
        result.setBestForTask(ranking.isEmpty() ? null : ranking.getFirst().getAgentName());

        AbilityComparisonEntity comparison = new AbilityComparisonEntity();
        comparison.setAgentNames(JsonUtil.toJson(request.getAgentNames()));
        comparison.setTaskContext(request.getTaskContext());
        comparison.setBestForTask(result.getBestForTask());
        comparison.setComparisonResult(JsonUtil.toJson(result));
        comparison.setComparisonTime(System.currentTimeMillis());
        abilityComparisonDao.insert(comparison);
        return result;
    }

    @Override
    public List<AbilityEvaluationDTO> history(String agentName) {
        return abilityEvaluationDao.findByAgentName(agentName).stream().map(this::toDTO).toList();
    }

    @Override
    public AbilityEvaluationDTO latest(String agentName) {
        return Optional.ofNullable(abilityEvaluationDao.findLatestByAgentName(agentName))
                .map(this::toDTO)
                .orElse(null);
    }

    @Override
    public List<AbilityEvaluationDTO> stats() {
        return abilityEvaluationDao.selectAll().stream()
                .sorted(Comparator.comparing(AbilityEvaluationEntity::getOverallScore,
                        Comparator.nullsLast(Integer::compareTo)).reversed())
                .map(this::toDTO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        abilityEvaluationDao.deleteById(id);
    }

    private AbilityEvaluationEntity buildEvaluation(AbilityEvaluationRequestDTO request) {
        AgentPersonaEntity persona = agentPersonaDao.findByName(request.getAgentName());
        List<AgentTaskMetaEntity> tasks = agentTaskMetaDao.findByAgentId(request.getAgentName());

        int power = score(request, "power", valueOrZero(persona == null ? null : persona.getPower()));
        int intelligence = score(request, "intelligence", valueOrZero(persona == null ? null : persona.getIntelligence()));
        int leadership = score(request, "leadership", valueOrZero(persona == null ? null : persona.getLeadership()));
        int abilityMatch = abilityMatchScore(persona, request.getRequiredAbilities());
        int taskScore = taskScore(tasks);
        int overall = clamp((power + intelligence + leadership + abilityMatch + taskScore) / 5);

        Map<String, Object> content = new HashMap<>();
        content.put("taskScore", taskScore);
        content.put("requiredAbilities", Optional.ofNullable(request.getRequiredAbilities()).orElseGet(List::of));
        content.put("scores", Optional.ofNullable(request.getScores()).orElseGet(Map::of));

        AbilityEvaluationEntity entity = new AbilityEvaluationEntity();
        entity.setAgentName(request.getAgentName());
        entity.setEvaluationType(Optional.ofNullable(request.getEvaluationType()).orElse("AUTO"));
        entity.setTaskContext(request.getTaskContext());
        entity.setPowerScore(power);
        entity.setIntelligenceScore(intelligence);
        entity.setLeadershipScore(leadership);
        entity.setAbilityMatchScore(abilityMatch);
        entity.setOverallScore(overall);
        entity.setEvaluationContent(JsonUtil.toJson(content));
        entity.setComment(request.getComment());
        entity.setEvaluationTime(System.currentTimeMillis());
        return entity;
    }

    private AbilityEvaluationDTO toDTO(AbilityEvaluationEntity entity) {
        AbilityEvaluationDTO dto = new AbilityEvaluationDTO();
        dto.setId(entity.getId());
        dto.setAgentName(entity.getAgentName());
        dto.setEvaluationType(entity.getEvaluationType());
        dto.setTaskContext(entity.getTaskContext());
        dto.setPowerScore(entity.getPowerScore());
        dto.setIntelligenceScore(entity.getIntelligenceScore());
        dto.setLeadershipScore(entity.getLeadershipScore());
        dto.setAbilityMatchScore(entity.getAbilityMatchScore());
        dto.setOverallScore(entity.getOverallScore());
        dto.setEvaluationContent(JsonUtil.jsonToMap(entity.getEvaluationContent()));
        dto.setComment(entity.getComment());
        dto.setEvaluationTime(entity.getEvaluationTime());
        return dto;
    }

    private int score(AbilityEvaluationRequestDTO request, String key, int fallback) {
        Integer value = Optional.ofNullable(request.getScores()).map(scores -> scores.get(key)).orElse(null);
        return clamp(value == null ? fallback : value);
    }

    private int abilityMatchScore(AgentPersonaEntity persona, List<String> requiredAbilities) {
        if (requiredAbilities == null || requiredAbilities.isEmpty()) {
            return 80;
        }
        if (persona == null || StringUtil.isBlank(persona.getAbilities())) {
            return 0;
        }
        List<String> abilities = JsonUtil.jsonToList(persona.getAbilities(), String.class);
        long matched = requiredAbilities.stream().filter(abilities::contains).count();
        return clamp((int) (matched * 100 / requiredAbilities.size()));
    }

    private int taskScore(List<AgentTaskMetaEntity> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return 70;
        }
        long completed = tasks.stream().filter(task -> "completed".equals(task.getRewardStatus())).count();
        long failed = tasks.stream().filter(task -> "failed".equals(task.getRewardStatus())).count();
        return clamp((int) (70 + completed * 5 - failed * 10));
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
