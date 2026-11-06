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
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
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
                String schema = outputSchema(candidates);
                modelStarted = System.nanoTime();
                try { raw = model.judge(inputJson, schema); }
                finally { modelEnded = System.nanoTime(); }
            } catch (Failure error) { throw error; }
            catch (Exception unavailable) { throw new Failure(503, "RECOMMENDATION_UNAVAILABLE"); }
            // A response is advice on exactly these facts, not an indefinitely valid admission token.
            AgentTaskDTO current = agents.getTaskRecommendationInput(taskId);
            if (!Objects.equals(task.getTaskVersion(), current.getTaskVersion())
                    || !Objects.equals(taskInput(task), taskInput(current))
                    || !Objects.equals(task.getStatus(), current.getStatus())) throw stale("TASK");
            var after = observe(actor, taskId);
            verifyCandidates(observation, after);
            try { materials.verifyUnchanged(actor, taskId, materialObservation); }
            catch (Failure failure) { if (failure.status() == 409) throw stale("MATERIAL"); throw failure; }
            // Display current status/name; recommendation is not execution admission.
            var judgedCandidates = new ArrayList<Candidate>();
            for (int i = 0; i < observation.candidates().size(); i++)
                judgedCandidates.add(new Candidate(after.candidates().get(i).agent(), observation.candidates().get(i).evidence()));
            var result = refreshAvailability(decode(taskId, task.getTaskVersion(), observedAt, judgedCandidates, raw), after.candidates());
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
    private record Observation(List<Candidate> candidates, List<ModelCandidate> modelCandidates,
            Map<String,String> providerOwners, Map<String,List<SkillManagementService.RecommendationSkill>> skillFacts) { }
    private static String candidateRef(int index) { return "A" + (index + 1); }
    private static String evidenceRef(int candidate, int evidence) { return candidateRef(candidate) + "E" + (evidence + 1); }
    private Observation observe(HostingRentHttp.Actor actor, String taskId) {
        var result = new ArrayList<Candidate>();
        var modelCandidates = new ArrayList<ModelCandidate>();
        long started = System.nanoTime();
        var available = agents.listTaskAssigneeCandidates(taskId).stream()
                .sorted(Comparator.comparing(row -> row.target().agentId())).toList();
        long listed = System.nanoTime();
        var skillFacts = available.isEmpty() ? Map.<String,List<SkillManagementService.RecommendationSkill>>of()
                : skills.recommendationSkills(actor, available);
        var providerOwners = new LinkedHashMap<String,String>();
        for (var runtime : available) {
            providerOwners.put(runtime.target().agentId(), runtime.skillProviderOwnerJiacn());
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
            int publicSkillIndex=0;
            for (var skill : skillFacts.getOrDefault(agent.agentId(), List.of())) {
                String description = agent.boundToMe()
                        ? "产品当前用途声明（不保证与安装版本完全一致）：" + string(skill.description())
                            + "；技能类型：" + skill.kind() + "；已安装版本：" + skill.skillVersion() + "；运行状态：" + skill.runtimeState()
                            + "；INSTRUCTION是原生指令经验，不是可直接执行的工具；技能证据不等于未报告的工具依赖可用，也不保证任务结果。"
                        : "接单技能用途：" + string(skill.description()) + "；运行状态：" + skill.runtimeState();
                descriptions.add(string(skill.description()));
                evidence.add(new Evidence(agent.boundToMe() ? "skill:" + skill.installationId() : "public-skill:" + (++publicSkillIndex),
                        "INSTALLED_SKILL", skill.title(), description, skill.available()));
            }
            if (!agent.boundToMe() && evidence.isEmpty()) {
                evidence.add(new Evidence("public-skills", "UNKNOWN", "暂无可确认的接单技能",
                        "当前没有可供推荐参考的接单技能摘要，不代表没有相关能力。", false));
                descriptions.add("暂无可确认的接单技能");
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
        return new Observation(List.copyOf(result), List.copyOf(modelCandidates), Map.copyOf(providerOwners), skillFacts);
    }
    private static Failure stale(String reason) {
        LOG.info("assignee_recommendation_stale reason={}", reason);
        return new Failure(409, "RECOMMENDATION_STALE");
    }
    private static void verifyCandidates(Observation before, Observation after) {
        if (!before.candidates().stream().map(c -> c.agent().agentId()).toList()
                .equals(after.candidates().stream().map(c -> c.agent().agentId()).toList())) throw stale("CANDIDATE_SET");
        if (!before.providerOwners().equals(after.providerOwners())) throw stale("CANDIDATE_FACTS");
        if (!stableSkills(before.skillFacts()).equals(stableSkills(after.skillFacts()))) throw stale("SKILL_EVIDENCE");
        for (int i = 0; i < before.candidates().size(); i++) {
            var old = before.candidates().get(i); var now = after.candidates().get(i);
            if (old.agent().boundToMe() != now.agent().boundToMe() || old.agent().canOperate() != now.agent().canOperate()
                    || !old.agent().abilities().equals(now.agent().abilities())) throw stale("CANDIDATE_FACTS");
        }
    }
    // A recommendation is advice, not execution admission. Catalog health may
    // change during a model call without changing what skill was installed.
    private record StableSkill(String installationId, String key, String version, String title, String description, String kind) { }
    private static Map<String,List<StableSkill>> stableSkills(Map<String,List<SkillManagementService.RecommendationSkill>> facts) {
        var result = new LinkedHashMap<String,List<StableSkill>>();
        facts.forEach((agent, rows) -> result.put(agent, rows.stream().map(s -> new StableSkill(
                s.installationId(), s.skillKey(), s.skillVersion(), s.title(), s.description(), s.kind())).toList()));
        return result;
    }
    private static Result refreshAvailability(Result result, List<Candidate> current) {
        var byAgent = new HashMap<String,Candidate>();
        current.forEach(c -> byAgent.put(c.agent().agentId(), c));
        var rows = result.recommendations().stream().map(old -> {
            var now = byAgent.get(old.agent().agentId());
            var byEvidence = new HashMap<String,Evidence>();
            now.evidence().forEach(e -> byEvidence.put(e.id(), e));
            var evidence = old.evidence().stream().map(e -> byEvidence.get(e.id())).toList();
            var gaps = new ArrayList<>(old.gaps());
            boolean unavailable = evidence.stream().anyMatch(e -> !e.available());
            if (unavailable) gaps.add("所引用技能当前未就绪；本次建议不代表可以立即执行，请以实际承办准入为准。");
            return new Recommendation(now.agent(), unavailable ? "UNKNOWN" : old.recommendationLevel(), old.reason(), evidence.stream().filter(Evidence::available).toList(), List.copyOf(gaps));
        }).toList();
        String state = rows.stream().anyMatch(r -> Set.of("HIGH", "MEDIUM").contains(r.recommendationLevel())) ? "READY" : "NO_MATCH";
        return new Result(result.taskId(), result.taskVersion(), result.observedAt(), state, result.requirementSummary(), rows, result.questions(), true);
    }
    // Business-specific output contract belongs to API; the model adapter only transports it.
    static String outputSchema(List<Candidate> candidates) throws com.fasterxml.jackson.core.JsonProcessingException {
        var rows = new LinkedHashMap<String,Object>();
        var string = Map.of("type", "string");
        var strings = Map.of("type", "array", "items", string);
        for (int i = 0; i < candidates.size(); i++) {
            var fields = new LinkedHashMap<String,Object>();
            fields.put("rank", Map.of("type", "integer"));
            fields.put("recommendationLevel", Map.of("type", "string", "enum", List.of("HIGH", "MEDIUM", "LOW", "UNKNOWN")));
            fields.put("reason", string); fields.put("evidenceIds", strings); fields.put("gaps", strings);
            rows.put(candidateRef(i), objectSchema(fields));
        }
        var root = new LinkedHashMap<String,Object>();
        root.put("requirementSummary", string); root.put("recommendations", objectSchema(rows));
        return JSON.writeValueAsString(objectSchema(root));
    }
    private static Map<String,Object> objectSchema(Map<String,Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    static Result decode(String taskId, String taskVersion, long observedAt, List<Candidate> candidates, String raw) {
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) throw invalid("JSON_OBJECT");
            String summary = text(root, "requirementSummary");
            // Questions are not part of the model contract; HTTP keeps its fixed empty array.
            if (root.has("questions")) throw invalid("QUESTIONS_NOT_SUPPORTED");
            JsonNode rows = root.get("recommendations");
            if (rows == null || !rows.isObject()) throw invalid("RECOMMENDATIONS_OBJECT");
            var byId = new HashMap<String, Candidate>();
            for (int i = 0; i < candidates.size(); i++) byId.put(candidateRef(i), candidates.get(i));
            var recommendations = new ArrayList<Recommendation>();
            var entries = new ArrayList<Map.Entry<String,JsonNode>>();
            rows.fields().forEachRemaining(entries::add);
            for (var entry : entries) {
                if (!byId.containsKey(entry.getKey())) throw invalid("CANDIDATE_UNKNOWN");
                if (!entry.getValue().path("rank").canConvertToLong() || !entry.getValue().path("rank").isIntegralNumber()) throw invalid("RANK");
            }
            if (entries.size() != byId.size()) throw invalid("CANDIDATE_COVERAGE");
            entries.sort(Comparator.<Map.Entry<String,JsonNode>>comparingLong(e -> e.getValue().path("rank").longValue())
                    .thenComparingInt(e -> Integer.parseInt(e.getKey().substring(1))));
            for (var entry : entries) {
                String id = entry.getKey(); JsonNode row = entry.getValue(); Candidate candidate = byId.get(id);
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
            String state = recommendations.stream().anyMatch(r -> Set.of("HIGH", "MEDIUM").contains(r.recommendationLevel()))
                    ? "READY" : "NO_MATCH";
            return new Result(taskId, taskVersion, observedAt, state, summary, List.copyOf(recommendations), List.of(), true);
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
