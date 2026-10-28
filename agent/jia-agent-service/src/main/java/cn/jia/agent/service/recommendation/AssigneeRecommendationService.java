package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentAssigneeRecommendationModel;
import cn.jia.agent.skill.SkillManagementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static cn.jia.agent.entity.AssigneeRecommendation.*;

/** API-owned observations + model advice. No assignment, billing, installation or transaction. */
@Service
public final class AssigneeRecommendationService {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AgentService agents;
    private final SkillManagementService skills;
    private final AgentAssigneeRecommendationModel model;
    public AssigneeRecommendationService(AgentService agents, SkillManagementService skills, AgentAssigneeRecommendationModel model) {
        this.agents = agents; this.skills = skills; this.model = model;
    }
    public Result recommend(HostingRentHttp.Actor actor, String taskId, Request request) {
        if (request == null || request.expectedTaskVersion() == null || request.expectedTaskVersion().isBlank())
            throw new Failure(400, "BAD_REQUEST");
        AgentTaskDTO task = agents.getTask(taskId);
        if (!Objects.equals(task.getTaskVersion(), request.expectedTaskVersion()) || !"open".equalsIgnoreCase(task.getStatus()))
            throw new Failure(409, "RECOMMENDATION_STALE");
        List<Candidate> candidates = observe(actor, taskId);
        long observedAt = Instant.now().toEpochMilli();
        if (candidates.isEmpty()) return new Result(taskId, task.getTaskVersion(), observedAt,
                "NO_CANDIDATES", "当前没有可承办候选", List.of(), List.of(), true);
        String raw;
        try {
            var input = new LinkedHashMap<String, Object>();
            input.put("task", taskInput(task)); input.put("candidates", candidates);
            input.put("materials", "未读取附件正文；不要猜测附件内容。必要时请用户补充。");
            raw = model.judge(JSON.writeValueAsString(input));
        } catch (Failure error) { throw error; }
        catch (Exception unavailable) { throw new Failure(503, "RECOMMENDATION_UNAVAILABLE"); }
        // A response is advice on exactly these facts, not an indefinitely valid admission token.
        AgentTaskDTO current = agents.getTask(taskId);
        if (!Objects.equals(task.getTaskVersion(), current.getTaskVersion())
                || !Objects.equals(taskInput(task), taskInput(current))
                || !Objects.equals(task.getStatus(), current.getStatus())
                || !Objects.equals(candidates, observe(actor, taskId)))
            throw new Failure(409, "RECOMMENDATION_STALE");
        return decode(taskId, task.getTaskVersion(), observedAt, candidates, raw);
    }
    private static Map<String, Object> taskInput(AgentTaskDTO task) {
        var input = new LinkedHashMap<String, Object>();
        input.put("title", task.getTitle()); input.put("description", task.getDescription());
        input.put("requiredAbilities", task.getRequiredAbilities());
        return input;
    }
    private List<Candidate> observe(HostingRentHttp.Actor actor, String taskId) {
        var result = new ArrayList<Candidate>();
        for (var runtime : agents.listTaskAssigneeCandidates(taskId)) {
            List<String> abilities = runtime.abilities();
            var target = runtime.target();
            var agent = new Agent(target.agentId(), target.displayName(), target.availability(), abilities,
                    true, target.isMine(), target.providerDisplayName());
            var evidence = new ArrayList<Evidence>();
            for (int i = 0; i < abilities.size(); i++) evidence.add(new Evidence("ability:" + i, "DECLARED_ABILITY",
                    abilities.get(i), "已登记本领，是能力声明而非完成保证。", true));
            if (agent.boundToMe()) {
                // Existing native management authority; do not revive a legacy runner-specific capability path.
                for (var skill : skills.recommendationSkills(actor, agent.agentId())) {
                    String description = "产品当前用途声明（不保证与安装版本完全一致）：" + string(skill.description())
                            + "；已安装版本：" + skill.skillVersion() + "；运行状态：" + skill.runtimeState()
                            + "；技能包就绪不等于未报告的工具依赖可用，也不保证任务结果。";
                    evidence.add(new Evidence("skill:" + skill.installationId(), "INSTALLED_SKILL",
                            skill.title(), description, skill.available()));
                }
            } else evidence.add(new Evidence("private-skills", "UNKNOWN", "技能信息未公开",
                    "外部候选仅提供接单公开摘要；未读取其私有技能、本领或成果。", false));
            result.add(new Candidate(agent, List.copyOf(evidence)));
        }
        return List.copyOf(result);
    }
    static Result decode(String taskId, String taskVersion, long observedAt, List<Candidate> candidates, String raw) {
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) throw invalid();
            String state = text(root, "state"), summary = text(root, "requirementSummary");
            if (!Set.of("READY", "NEEDS_CLARIFICATION", "NO_MATCH").contains(state)) throw invalid();
            List<String> questions = strings(root.get("questions"));
            JsonNode rows = root.get("recommendations");
            if (rows == null || !rows.isArray()) throw invalid();
            var byId = new HashMap<String, Candidate>();
            candidates.forEach(c -> byId.put(c.agent().agentId(), c));
            var seen = new HashSet<String>(); var recommendations = new ArrayList<Recommendation>();
            for (JsonNode row : rows) {
                String id = text(row, "agentId"); Candidate candidate = byId.get(id);
                if (candidate == null || !seen.add(id) || !candidate.agent().canOperate()) throw invalid();
                List<String> ids = strings(row.get("evidenceIds"));
                if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) throw invalid();
                var evidence = new ArrayList<Evidence>();
                for (String evidenceId : ids) evidence.add(candidate.evidence().stream()
                        .filter(e -> e.id().equals(evidenceId) && e.available()).findFirst().orElseThrow(AssigneeRecommendationService::invalid));
                var gaps = new ArrayList<>(strings(row.get("gaps")));
                if (!"online".equalsIgnoreCase(candidate.agent().status())) gaps.add("当前运行状态：" + candidate.agent().status() + "；确认承办不代表已开始运行。");
                recommendations.add(new Recommendation(candidate.agent(), text(row, "reason"), List.copyOf(evidence), List.copyOf(gaps)));
            }
            if ("READY".equals(state) != !recommendations.isEmpty()
                    || "NEEDS_CLARIFICATION".equals(state) && questions.isEmpty()) throw invalid();
            return new Result(taskId, taskVersion, observedAt, state, summary, List.copyOf(recommendations), questions, true);
        } catch (Failure failure) { throw failure; }
        catch (Exception invalid) { throw invalid(); }
    }
    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) throw invalid();
        var values = new ArrayList<String>();
        for (JsonNode item : node) { if (!item.isTextual() || item.asText().isBlank()) throw invalid(); values.add(item.asText()); }
        return List.copyOf(values);
    }
    private static String text(JsonNode node, String key) {
        if (node == null || !node.path(key).isTextual() || node.path(key).asText().isBlank()) throw invalid();
        return node.path(key).asText();
    }
    private static String string(Object value) { return value instanceof String text ? text : ""; }
    private static Failure invalid() { return new Failure(502, "RECOMMENDATION_INVALID_RESULT"); }
}
