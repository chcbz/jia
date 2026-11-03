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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AssigneeRecommendationService.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AgentService agents;
    private final SkillManagementService skills;
    private final AgentAssigneeRecommendationModel model;
    private final RecommendationMaterials materials;
    public AssigneeRecommendationService(AgentService agents, SkillManagementService skills, AgentAssigneeRecommendationModel model, RecommendationMaterials materials) {
        this.agents = agents; this.skills = skills; this.model = model; this.materials = materials;
    }
    public Result recommend(HostingRentHttp.Actor actor, String taskId, Request request) {
        long started = System.nanoTime(), modelStarted = 0, modelEnded = 0;
        String outcome = "failure";
        try {
            if (request == null || request.expectedTaskVersion() == null || request.expectedTaskVersion().isBlank())
                throw new Failure(400, "BAD_REQUEST");
            AgentTaskDTO task = agents.getTaskRecommendationInput(taskId);
            if (!Objects.equals(task.getTaskVersion(), request.expectedTaskVersion()) || !"open".equalsIgnoreCase(task.getStatus()))
                throw new Failure(409, "RECOMMENDATION_STALE");
            var observation = observe(actor, taskId);
            List<Candidate> candidates = observation.candidates();
            long observedAt = Instant.now().toEpochMilli();
            if (candidates.isEmpty()) {
                outcome = "no_candidates";
                return new Result(taskId, task.getTaskVersion(), observedAt,
                        "NO_CANDIDATES", "当前没有可承办候选", List.of(), List.of(), true);
            }
            var materialObservation = materials.observe(actor, taskId);
            String raw;
            try {
                var input = new LinkedHashMap<String, Object>();
                input.put("task", taskInput(task)); input.put("candidates", observation.modelCandidates());
                input.put("materials", materialObservation.stream().map(o -> {
                    var m = o.material();
                    return new ModelMaterial(m.name(), m.originalFilename(), m.contentMimeType(), m.role(), m.summary());
                }).toList());
                String inputJson = JSON.writeValueAsString(input);
                modelStarted = System.nanoTime();
                try { raw = model.judge(inputJson); }
                finally { modelEnded = System.nanoTime(); }
            } catch (Failure error) { throw error; }
            catch (Exception unavailable) { throw new Failure(503, "RECOMMENDATION_UNAVAILABLE"); }
            // A response is advice on exactly these facts, not an indefinitely valid admission token.
            AgentTaskDTO current = agents.getTaskRecommendationInput(taskId);
            if (!Objects.equals(task.getTaskVersion(), current.getTaskVersion())
                    || !Objects.equals(taskInput(task), taskInput(current))
                    || !Objects.equals(task.getStatus(), current.getStatus())
                    || !Objects.equals(observation, observe(actor, taskId)))
                throw new Failure(409, "RECOMMENDATION_STALE");
            materials.verifyUnchanged(actor, taskId, materialObservation);
            var result = decode(taskId, task.getTaskVersion(), observedAt, candidates, raw);
            outcome = "success";
            return result;
        } catch (Failure failure) {
            outcome = failure.status() == 409 ? "stale" : "failure";
            throw failure;
        } finally {
            long ended = System.nanoTime();
            long modelNanos = modelEnded == 0 ? 0 : modelEnded - modelStarted;
            // No prompt, attachment metadata, identities, model response or credentials in logs.
            LOG.info("assignee_recommendation_timing outcome={} totalMs={} nonModelMs={} beforeModelMs={} modelMs={} afterModelMs={}",
                    outcome, millis(ended - started), millis(ended - started - modelNanos),
                    millis((modelStarted == 0 ? ended : modelStarted) - started), millis(modelNanos),
                    modelEnded == 0 ? 0 : millis(ended - modelEnded));
        }
    }
    private static long millis(long nanos) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos); }
    private static Map<String, Object> taskInput(AgentTaskDTO task) {
        var input = new LinkedHashMap<String, Object>();
        input.put("title", task.getTitle()); input.put("description", task.getDescription());
        input.put("requiredAbilities", task.getRequiredAbilities());
        return input;
    }
    // Model references exist only within this request. Full facts remain in Observation for stale checks and HTTP output.
    private record ModelMaterial(String name, String originalFilename, String contentMimeType, String role, String summary) { }
    private record ModelEvidence(String id, String kind, String label, String description, boolean available) { }
    private record ModelCandidate(String agentId, boolean boundToMe, List<ModelEvidence> evidence) { }
    private record Observation(List<Candidate> candidates, List<ModelCandidate> modelCandidates) { }
    private static String candidateRef(int index) { return "A" + (index + 1); }
    private static String evidenceRef(int candidate, int evidence) { return candidateRef(candidate) + "E" + (evidence + 1); }
    private Observation observe(HostingRentHttp.Actor actor, String taskId) {
        var result = new ArrayList<Candidate>();
        var modelCandidates = new ArrayList<ModelCandidate>();
        long started = System.nanoTime();
        var available = agents.listTaskAssigneeCandidates(taskId);
        long listed = System.nanoTime();
        for (var runtime : available) {
            List<String> abilities = runtime.abilities();
            var target = runtime.target();
            var agent = new Agent(target.agentId(), target.displayName(), target.availability(), abilities,
                    true, target.isMine(), target.providerDisplayName());
            var evidence = new ArrayList<Evidence>();
            var descriptions = new ArrayList<String>();
            for (int i = 0; i < abilities.size(); i++) {
                evidence.add(new Evidence("ability:" + i, "DECLARED_ABILITY",
                        abilities.get(i), "已登记本领，是能力声明而非完成保证。", true));
                descriptions.add("");
            }
            if (agent.boundToMe()) {
                // Existing native management authority; do not revive a legacy runner-specific capability path.
                for (var skill : skills.recommendationSkills(actor, agent.agentId())) {
                    String description = "产品当前用途声明（不保证与安装版本完全一致）：" + string(skill.description())
                            + "；技能类型：" + skill.kind() + "；已安装版本：" + skill.skillVersion() + "；运行状态：" + skill.runtimeState()
                            + "；INSTRUCTION是原生指令经验，不是可直接执行的工具；技能证据不等于未报告的工具依赖可用，也不保证任务结果。";
                    descriptions.add(string(skill.description()));
                    evidence.add(new Evidence("skill:" + skill.installationId(), "INSTALLED_SKILL",
                            skill.title(), description, skill.available()));
                }
            } else {
                evidence.add(new Evidence("private-skills", "UNKNOWN", "技能信息未公开",
                        "外部候选仅提供接单公开摘要；未读取其私有技能、本领或成果。", false));
                descriptions.add("技能信息未公开");
            }
            var modelEvidence = new ArrayList<ModelEvidence>();
            for (int i = 0; i < evidence.size(); i++) {
                var e = evidence.get(i);
                modelEvidence.add(new ModelEvidence(evidenceRef(result.size(), i), e.kind(), e.label(),
                        descriptions.get(i), e.available()));
            }
            modelCandidates.add(new ModelCandidate(candidateRef(result.size()), agent.boundToMe(), List.copyOf(modelEvidence)));
            result.add(new Candidate(agent, List.copyOf(evidence)));
        }
        LOG.info("assignee_recommendation_observe candidates={} candidateMs={} evidenceMs={}",
                result.size(), millis(listed - started), millis(System.nanoTime() - listed));
        return new Observation(List.copyOf(result), List.copyOf(modelCandidates));
    }
    static Result decode(String taskId, String taskVersion, long observedAt, List<Candidate> candidates, String raw) {
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) throw invalid("JSON_OBJECT");
            String summary = text(root, "requirementSummary");
            List<String> questions = strings(root.get("questions"));
            if (!questions.isEmpty()) throw invalid("QUESTIONS_NOT_SUPPORTED");
            JsonNode rows = root.get("recommendations");
            if (rows == null || !rows.isArray()) throw invalid("RECOMMENDATIONS_ARRAY");
            var byId = new HashMap<String, Candidate>();
            for (int i = 0; i < candidates.size(); i++) byId.put(candidateRef(i), candidates.get(i));
            var seen = new HashSet<String>(); var recommendations = new ArrayList<Recommendation>();
            for (JsonNode row : rows) {
                String id = text(row, "agentId"); Candidate candidate = byId.get(id);
                if (candidate == null) throw invalid("CANDIDATE_UNKNOWN");
                if (!seen.add(id)) throw invalid("CANDIDATE_DUPLICATE");
                if (!candidate.agent().canOperate()) throw invalid("CANDIDATE_NOT_OPERABLE");
                String level = text(row, "recommendationLevel");
                if (!Set.of("HIGH", "MEDIUM", "LOW", "UNKNOWN").contains(level)) throw invalid("LEVEL");
                List<String> ids = strings(row.get("evidenceIds"));
                if ((!"UNKNOWN".equals(level) && ids.isEmpty()) || new HashSet<>(ids).size() != ids.size()) throw invalid("EVIDENCE_IDS");
                var evidence = new ArrayList<Evidence>();
                var byEvidenceRef = new HashMap<String, Evidence>();
                int candidateIndex = candidates.indexOf(candidate);
                for (int i = 0; i < candidate.evidence().size(); i++)
                    byEvidenceRef.put(evidenceRef(candidateIndex, i), candidate.evidence().get(i));
                for (String evidenceId : ids) {
                    var fact = byEvidenceRef.get(evidenceId);
                    if (fact == null || !fact.available()) throw invalid("EVIDENCE_UNAVAILABLE");
                    evidence.add(fact);
                }
                var gaps = new ArrayList<>(strings(row.get("gaps")));
                if (!"online".equalsIgnoreCase(candidate.agent().status())) gaps.add("当前运行状态：" + candidate.agent().status() + "；确认承办不代表已开始运行。");
                recommendations.add(new Recommendation(candidate.agent(), level, text(row, "reason"), List.copyOf(evidence), List.copyOf(gaps)));
            }
            if (seen.size() != byId.size()) throw invalid("CANDIDATE_COVERAGE");
            String state = recommendations.stream().anyMatch(r -> Set.of("HIGH", "MEDIUM").contains(r.recommendationLevel()))
                    ? "READY" : "NO_MATCH";
            return new Result(taskId, taskVersion, observedAt, state, summary, List.copyOf(recommendations), questions, true);
        } catch (Failure failure) { throw failure; }
        catch (Exception invalid) { throw invalid("JSON_DECODE"); }
    }
    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) throw invalid("STRING_ARRAY");
        var values = new ArrayList<String>();
        for (JsonNode item : node) { if (!item.isTextual() || item.asText().isBlank()) throw invalid("STRING_ITEM"); values.add(item.asText()); }
        return List.copyOf(values);
    }
    private static String text(JsonNode node, String key) {
        if (node == null || !node.path(key).isTextual() || node.path(key).asText().isBlank()) throw invalid("TEXT_FIELD_" + key);
        return node.path(key).asText();
    }
    private static String string(Object value) { return value instanceof String text ? text : ""; }
    private static Failure invalid(String reason) {
        // Constant structural reason only: never log model text, task content, identities or credentials.
        LOG.warn("assignee_recommendation_invalid reason={}", reason);
        return new Failure(502, "RECOMMENDATION_INVALID_RESULT");
    }
}
