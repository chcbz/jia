package cn.jia.agent.service;

import cn.jia.agent.entity.AbilityCompareRequestDTO;
import cn.jia.agent.entity.AbilityCompareResultDTO;
import cn.jia.agent.entity.AbilityEvaluationDTO;
import cn.jia.agent.entity.AbilityEvaluationRequestDTO;

import java.util.List;

public interface AbilityEvaluationService {
    AbilityEvaluationDTO evaluate(AbilityEvaluationRequestDTO request);

    AbilityCompareResultDTO compare(AbilityCompareRequestDTO request);

    List<AbilityEvaluationDTO> history(String agentName);

    AbilityEvaluationDTO latest(String agentName);

    List<AbilityEvaluationDTO> stats();

    void delete(Long id);
}
