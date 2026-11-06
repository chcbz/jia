package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskAssigneeCandidateDTO;
import cn.jia.agent.entity.AgentTaskTargetSummaryDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentAssigneeRecommendationModel;
import cn.jia.agent.skill.SkillManagementService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static cn.jia.agent.entity.AssigneeRecommendation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssigneeRecommendationServiceTest {
    private static final HostingRentHttp.Actor ACTOR = new HostingRentHttp.Actor("user", "0", "client", "owner");
    private static final String ANSWER = """
        {"state":"READY","requirementSummary":"写说明","recommendations":{
        "A1":{"rank":1,"recommendationLevel":"HIGH","reason":"登记写作本领","evidenceIds":["A1E1"],"gaps":[]}}}
        """;
    private static Candidate candidate() { return new Candidate(new Agent("a", "Agent", "online", List.of("写作"), true, true, null),
            List.of(new Evidence("ability:0", "DECLARED_ABILITY", "写作", "登记声明", true))); }
    private static AgentTaskDTO task(String version) {
        var t = new AgentTaskDTO(); t.setId("task"); t.setTitle("写说明"); t.setDescription("说明怎么用");
        t.setTaskVersion(version); t.setStatus("open"); t.setRequiredAbilities(List.of()); return t;
    }
    @Test void projectsOnlyServerAgentAndEvidenceAllowsHarmlessFields() {
        var result = AssigneeRecommendationService.decode("task", "7", 1000, List.of(candidate()), ANSWER.replace("\"state\":", "\"futureField\":true,\"state\":"));
        assertEquals(candidate().agent(), result.recommendations().getFirst().agent());
        assertEquals(candidate().evidence(), result.recommendations().getFirst().evidence());
        assertEquals("7", result.taskVersion());
    }
    @Test void rejectsForgedAgentEvidenceUnavailableFactMalformedStateAndDuplicateCandidate() {
        for (String invalid : List.of(ANSWER.replace("\"A1\"", "\"foreign\""), ANSWER.replace("A1E1", "tool:imaginary"),
                ANSWER.replace("HIGH", "INVALID"), ANSWER.replace("[\"A1E1\"]", "[]"),
                ANSWER.replace("HIGH", ""), ANSWER + "{}", "[]")) {
            assertEquals(502, assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1, List.of(candidate()), invalid)).status());
        }
        var unavailable = new Candidate(candidate().agent(), List.of(new Evidence("ability:0", "SKILL", "写作", "未就绪", false)));
        assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1, List.of(unavailable), ANSWER));
    }
    @Test void offlineCandidateRemainsRecommendableWithExplicitWaitingGap() {
        var offline = new Candidate(new Agent("a", "Agent", "offline", List.of("写作"), true, true, null), candidate().evidence());
        var result = AssigneeRecommendationService.decode("task", "7", 1, List.of(offline), ANSWER);
        assertEquals("READY", result.state());
        assertTrue(result.recommendations().getFirst().gaps().stream().anyMatch(gap -> gap.contains("不代表已开始运行")));
    }
    @Test void lowAndUnknownStillReturnIndividualAssessmentsAndQuestionsAreRejected() {
        for (String level : List.of("LOW", "UNKNOWN")) {
            String answer = ANSWER.replace("HIGH", level);
            if ("UNKNOWN".equals(level)) answer = answer.replace("[\"A1E1\"]", "[]");
            var result = AssigneeRecommendationService.decode("task", "7", 1, List.of(candidate()), answer);
            assertEquals("NO_MATCH", result.state());
            assertEquals(level, result.recommendations().getFirst().recommendationLevel());
            assertEquals(1, result.recommendations().size());
        }
        assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1, List.of(candidate()),
                ANSWER.replace("\"state\":", "\"questions\":[\"请补充\"],\"state\":")));
    }
    @Test void requiresEveryCandidateExactlyOnceWithoutInventingAssessments() {
        var other = new Candidate(new Agent("b", "Other", "online", List.of(), true, false, null), List.of());
        assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1, List.of(candidate(), other), ANSWER));
        String row = "\"A1\":{\"rank\":1,\"recommendationLevel\":\"HIGH\",\"reason\":\"写作\",\"evidenceIds\":[\"A1E1\"],\"gaps\":[]}";
        assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1, List.of(candidate()),
                "{\"requirementSummary\":\"写作\",\"recommendations\":{" + row + "," + row + "}}"));
        assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1, List.of(candidate()),
                "{\"requirementSummary\":\"写作\",\"recommendations\":{}}"));
    }
    @Test void noCandidatesMakesNoModelOrSkillCallAndVersionMismatchDoesNotInvokeModel() {
        var agents = mock(AgentService.class); var skills = mock(SkillManagementService.class); var model = mock(AgentAssigneeRecommendationModel.class);
        var service = new AssigneeRecommendationService(agents, skills, model, mock(RecommendationMaterials.class));
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7")); when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of());
        assertEquals("NO_CANDIDATES", service.recommend(ACTOR, "task", new Request("7")).state());
        assertEquals(409, assertThrows(Failure.class, () -> service.recommend(ACTOR, "task", new Request("6"))).status());
        verifyNoInteractions(skills, model);
    }
    @Test void usesSingleCallNoAssignmentAndChecksTaskAgainAfterInference() {
        var agents = mock(AgentService.class); var skills = mock(SkillManagementService.class); var model = mock(AgentAssigneeRecommendationModel.class);
        var service = new AssigneeRecommendationService(agents, skills, model, mock(RecommendationMaterials.class));
        var a = new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a", "Agent", null, true, "online"), List.of("写作"), "owner");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7")); when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(a));
        when(skills.recommendationSkills(eq(ACTOR), anyList())).thenReturn(Map.of()); when(model.judge(anyString(), anyString())).thenReturn(ANSWER);
        assertEquals("READY", service.recommend(ACTOR, "task", new Request("7")).state());
        verify(model, times(1)).judge(anyString(), anyString()); verify(agents, never()).assignTask(anyString(), any());
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7"), task("8"));
        assertEquals(409, assertThrows(Failure.class, () -> service.recommend(ACTOR, "task", new Request("7"))).status());
    }
    @Test void externalCandidateWithoutPublicSkillsRemainsUnknown() {
        var agents = mock(AgentService.class); var skills = mock(SkillManagementService.class); var model = mock(AgentAssigneeRecommendationModel.class);
        var a = new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("external", "Agent", "公开提供方", false, "online"), List.of(), "owner");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7")); when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(a));
        when(model.judge(anyString(), anyString())).thenReturn(ANSWER.replace("HIGH", "UNKNOWN").replace("[\"A1E1\"]", "[]"));
        var service = new AssigneeRecommendationService(agents, skills, model, mock(RecommendationMaterials.class));
        var result = service.recommend(ACTOR, "task", new Request("7"));
        assertEquals("NO_MATCH", result.state());
        assertEquals("UNKNOWN", result.recommendations().getFirst().recommendationLevel());
        verify(skills, times(2)).recommendationSkills(ACTOR, List.of(a)); verify(model, times(1)).judge(anyString(), anyString());
    }
    @Test void providerFailureDoesNotRetryOrLeakItsMessage() {
        var agents = mock(AgentService.class); var skills = mock(SkillManagementService.class); var model = mock(AgentAssigneeRecommendationModel.class);
        var a = new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a", "Agent", null, true, "online"), List.of("写作"), "owner");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7")); when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(a));
        when(skills.recommendationSkills(eq(ACTOR), anyList())).thenReturn(Map.of());
        when(model.judge(anyString(), anyString())).thenThrow(new RuntimeException("private upstream details"));
        var service = new AssigneeRecommendationService(agents, skills, model, mock(RecommendationMaterials.class));
        Failure failure = assertThrows(Failure.class, () -> service.recommend(ACTOR, "task", new Request("7")));
        assertEquals("RECOMMENDATION_UNAVAILABLE", failure.code()); verify(model, times(1)).judge(anyString(), anyString());
        verify(agents, never()).assignTask(anyString(), any());
    }
    @Test void installedDisabledSkillIsNeverPositiveEvidence() {
        var agent = candidate().agent();
        var c = new Candidate(agent, List.of(new Evidence("skill:installed", "INSTALLED_SKILL", "图像", "已停用", false)));
        assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1, List.of(c), ANSWER));
    }

    @Test void allSelectableCandidatesAreSentInOneCallIncludingUnknownCapabilities() throws Exception {
        var agents = mock(AgentService.class); var skills = mock(SkillManagementService.class); var model = mock(AgentAssigneeRecommendationModel.class);
        var eligible = new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a", "Writer", null, true, "online"), List.of("写作"), "owner");
        var impossible = new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("no-evidence", "Empty", null, true, "online"), List.of(), "owner");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7"));
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(impossible, eligible));
        when(skills.recommendationSkills(eq(ACTOR), anyList())).thenReturn(Map.of());
        String all = ANSWER.replace("\"gaps\":[]}}}", "\"gaps\":[]},\"A2\":{\"rank\":2,\"recommendationLevel\":\"UNKNOWN\",\"reason\":\"资料不足\",\"evidenceIds\":[],\"gaps\":[]}}}");
        when(model.judge(anyString(), anyString())).thenReturn(all);
        var result = new AssigneeRecommendationService(agents, skills, model, mock(RecommendationMaterials.class)).recommend(ACTOR, "task", new Request("7"));
        assertEquals("READY", result.state()); assertTrue(result.candidateSetComplete());
        var input = org.mockito.ArgumentCaptor.forClass(String.class); verify(model, times(1)).judge(input.capture(), anyString());
        var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input.getValue()).get("candidates");
        assertEquals(2, rows.size()); assertEquals("A1", rows.get(0).get("agentId").asText());
        assertFalse(rows.toString().contains("no-evidence")); assertFalse(rows.get(0).has("agent"));
        assertEquals(2, result.recommendations().size());
        verify(agents, times(2)).listTaskAssigneeCandidates("task");
    }
    @Test void installedButUnavailableEvidenceCanOnlySupportUnknownWithoutCitation() {
        var agents = mock(AgentService.class); var skills = mock(SkillManagementService.class); var model = mock(AgentAssigneeRecommendationModel.class);
        var a = new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a", "Agent", null, true, "online"), List.of(), "owner");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7")); when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(a));
        when(skills.recommendationSkills(eq(ACTOR), anyList())).thenReturn(Map.of("a", List.of(new SkillManagementService.RecommendationSkill(
                "installed", "image", "1", "图片", "经验", false, "VERSION_MISMATCH", "INSTRUCTION"))));
        when(model.judge(anyString(), anyString())).thenReturn(ANSWER.replace("HIGH", "UNKNOWN").replace("[\"A1E1\"]", "[]"));
        var result = new AssigneeRecommendationService(agents, skills, model, mock(RecommendationMaterials.class)).recommend(ACTOR, "task", new Request("7"));
        assertEquals("NO_MATCH", result.state()); assertEquals("UNKNOWN", result.recommendations().getFirst().recommendationLevel());
        verify(model, times(1)).judge(anyString(), anyString());
    }
    @Test void reproducedEmptyEvidenceResultRemainsRejectedRatherThanBeingFilledIn() {
        var invalid = ANSWER.replace("[\"A1E1\"]", "[]");
        var failure = assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1, List.of(candidate()), invalid));
        assertEquals(502, failure.status()); assertEquals("RECOMMENDATION_INVALID_RESULT", failure.code());
    }
    @Test void enabledInstructionExperienceCanBeCitedWithoutClaimingToolAdmission() throws Exception {
        var agents = mock(AgentService.class); var skills = mock(SkillManagementService.class); var model = mock(AgentAssigneeRecommendationModel.class);
        var a = new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a", "Agent", null, true, "online"), List.of(), "owner");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7")); when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(a));
        when(skills.recommendationSkills(eq(ACTOR), anyList())).thenReturn(Map.of("a", List.of(new SkillManagementService.RecommendationSkill(
                "installed", "writing", "1", "写作", "写作经验", true, "NOT_EXECUTABLE", "INSTRUCTION"))));
        when(model.judge(anyString(), anyString())).thenReturn(ANSWER);
        var result = new AssigneeRecommendationService(agents, skills, model, mock(RecommendationMaterials.class)).recommend(ACTOR, "task", new Request("7"));
        assertEquals("READY", result.state());
        var evidence = result.recommendations().getFirst().evidence().getFirst();
        assertEquals("skill:installed", evidence.id()); assertTrue(evidence.available());
        assertTrue(evidence.description().contains("不是可直接执行的工具"));
        var captured = org.mockito.ArgumentCaptor.forClass(String.class); verify(model).judge(captured.capture(), anyString());
        var projected = new com.fasterxml.jackson.databind.ObjectMapper().readTree(captured.getValue()).path("candidates").get(0);
        assertEquals("写作经验", projected.path("evidence").get(0).path("description").asText());
        assertFalse(captured.getValue().contains("NOT_EXECUTABLE"));
        assertFalse(projected.has("abilities")); assertFalse(projected.has("canOperate"));
        verify(model, times(1)).judge(anyString(), anyString()); verify(agents, never()).assignTask(anyString(), any());
    }

    @Test void imageMetadataEntersSingleModelCallWithoutPrivateSnapshotFields() throws Exception {
        var agents = mock(AgentService.class); var skills = mock(SkillManagementService.class);
        var model = mock(AgentAssigneeRecommendationModel.class); var materials = mock(RecommendationMaterials.class);
        var t = task("7"); t.setTitle("分析结果"); t.setDescription("分析结果");
        when(agents.getTaskRecommendationInput("task")).thenReturn(t);
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(new AgentTaskAssigneeCandidateDTO(
                new AgentTaskTargetSummaryDTO("a", "Agent", null, true, "online"), List.of("识图分析"), "owner")));
        when(skills.recommendationSkills(eq(ACTOR), anyList())).thenReturn(Map.of());
        var snapshot = List.of(new RecommendationMaterials.Observation("private-relation", 9, "private-file", 10,
                "private-sha", new RecommendationMaterials.Material("检测图片", "result.png", "image/png", 1, "INPUT", "ACTIVE", null)));
        when(materials.observe(ACTOR, "task")).thenReturn(snapshot);
        when(model.judge(anyString(), anyString())).thenReturn(ANSWER);
        var service = new AssigneeRecommendationService(agents, skills, model, materials);
        assertEquals("READY", service.recommend(ACTOR, "task", new Request("7")).state());
        var input = org.mockito.ArgumentCaptor.forClass(String.class); verify(model, times(1)).judge(input.capture(), anyString());
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input.getValue());
        assertEquals("分析结果", root.path("task").path("description").asText());
        assertEquals("image/png", root.path("materials").get(0).path("contentMimeType").asText());
        assertEquals("检测图片", root.path("materials").get(0).path("name").asText());
        assertTrue(root.path("materials").get(0).path("summary").isNull());
        assertFalse(input.getValue().contains("private-"));
        assertFalse(root.path("materials").get(0).has("version"));
        assertFalse(root.path("materials").get(0).has("fileState"));
        verify(materials).verifyUnchanged(ACTOR, "task", snapshot);
        doThrow(new Failure(409, "RECOMMENDATION_STALE")).when(materials).verifyUnchanged(ACTOR, "task", snapshot);
        assertEquals(409, assertThrows(Failure.class, () -> service.recommend(ACTOR, "task", new Request("7"))).status());
        reset(model); when(materials.observe(ACTOR, "task")).thenThrow(new Failure(409, "RECOMMENDATION_STALE"));
        assertThrows(Failure.class, () -> service.recommend(ACTOR, "task", new Request("7")));
        verifyNoInteractions(model);
    }

    @Test void aliasesMapToThisRequestOnlyAndCannotCrossCandidateEvidence() {
        var other = new Candidate(new Agent("other-real-id", "Other", "online", List.of("写作"), true, true, null),
                List.of(new Evidence("private-other-evidence", "DECLARED_ABILITY", "写作", "", true)));
        var mapped = AssigneeRecommendationService.decode("task", "7", 1, List.of(other), ANSWER);
        assertEquals("other-real-id", mapped.recommendations().getFirst().agent().agentId());
        assertEquals("private-other-evidence", mapped.recommendations().getFirst().evidence().getFirst().id());
        String second = "\"A2\":{\"rank\":2,\"recommendationLevel\":\"HIGH\",\"reason\":\"适合\",\"evidenceIds\":[\"A2E1\"],\"gaps\":[]}";
        String both = ANSWER.replace("\"gaps\":[]}}}", "\"gaps\":[]}," + second + "}}");
        assertEquals(2, AssigneeRecommendationService.decode("task", "7", 1, List.of(candidate(), other), both).recommendations().size());
        assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1,
                List.of(candidate(), other), both.replace("A2E1", "A1E1")));
        assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task", "7", 1,
                List.of(candidate()), ANSWER.replace("\"A1\"", "\"a\"")));
    }

    @Test void schemaRequiresEveryCandidateAndRankControlsOrderWithStableTies() throws Exception {
        var other = new Candidate(new Agent("b", "Other", "online", List.of(), true, false, null), List.of());
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(AssigneeRecommendationService.outputSchema(List.of(candidate(), other)));
        assertEquals("[\"A1\",\"A2\"]", schema.path("properties").path("recommendations").path("required").toString());
        assertFalse(schema.path("properties").path("recommendations").path("additionalProperties").asBoolean());
        String both = ANSWER.replace("\"gaps\":[]}}}", "\"gaps\":[]},\"A2\":{\"rank\":1,\"recommendationLevel\":\"UNKNOWN\",\"reason\":\"不足\",\"evidenceIds\":[],\"gaps\":[]}}}");
        assertEquals("a", AssigneeRecommendationService.decode("task","7",1,List.of(candidate(),other),both).recommendations().getFirst().agent().agentId());
        String ranked = both.replace("\"A1\":{\"rank\":1", "\"A1\":{\"rank\":2");
        assertEquals("b", AssigneeRecommendationService.decode("task","7",1,List.of(candidate(),other),ranked).recommendations().getFirst().agent().agentId());
        assertThrows(Failure.class, () -> AssigneeRecommendationService.decode("task","7",1,List.of(candidate()),ANSWER.replace("\"rank\":1", "\"rank\":\"first\"")));
    }
    @Test void statusOnlyChangeUsesFreshDisplayWhileRealCapabilityChangeIsStale() {
        var agents=mock(AgentService.class);var skills=mock(SkillManagementService.class);var model=mock(AgentAssigneeRecommendationModel.class);
        var service=new AssigneeRecommendationService(agents,skills,model,mock(RecommendationMaterials.class));
        var online=new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a","Old",null,true,"online"),List.of("写作"), "owner");
        var offline=new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a","New",null,true,"offline"),List.of("写作"), "owner");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7"));
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(online),List.of(offline));
        when(model.judge(anyString(),anyString())).thenReturn(ANSWER);
        var result=service.recommend(ACTOR,"task",new Request("7"));
        assertEquals("New",result.recommendations().getFirst().agent().name());
        assertTrue(result.recommendations().getFirst().gaps().stream().anyMatch(g->g.contains("不代表已开始运行")));
        var changed=new AgentTaskAssigneeCandidateDTO(online.target(),List.of("其他本领"), "owner");
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(online),List.of(changed));
        assertEquals(409,assertThrows(Failure.class,()->service.recommend(ACTOR,"task",new Request("7"))).status());
    }
    @Test void transientSkillReadinessChangeReturnsCurrentAdviceWithoutRetryingModel() {
        var agents=mock(AgentService.class);var skills=mock(SkillManagementService.class);var model=mock(AgentAssigneeRecommendationModel.class);
        var service=new AssigneeRecommendationService(agents,skills,model,mock(RecommendationMaterials.class));
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7"));
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(new AgentTaskAssigneeCandidateDTO(
                new AgentTaskTargetSummaryDTO("a","A",null,true,"online"),List.of(), "owner")));
        var ready=new SkillManagementService.RecommendationSkill("installation","vision","1.0.0","识图","读图片",true,"NOT_EXECUTABLE","INSTRUCTION");
        var disabled=new SkillManagementService.RecommendationSkill("installation","vision","1.0.0","识图","读图片",false,"UNKNOWN","INSTRUCTION");
        when(skills.recommendationSkills(eq(ACTOR), anyList())).thenReturn(Map.of("a",List.of(ready)),Map.of("a",List.of(disabled)));
        when(model.judge(anyString(),anyString())).thenReturn(ANSWER);
        var result=service.recommend(ACTOR,"task",new Request("7"));
        assertEquals("NO_MATCH",result.state());
        assertEquals("UNKNOWN",result.recommendations().getFirst().recommendationLevel());
        assertTrue(result.recommendations().getFirst().evidence().isEmpty());
        assertTrue(result.recommendations().getFirst().gaps().stream().anyMatch(g->g.contains("技能当前未就绪")));
        verify(model,times(1)).judge(anyString(),anyString());
        var upgraded=new SkillManagementService.RecommendationSkill("installation","vision","2.0.0","识图","读图片",true,"NOT_EXECUTABLE","INSTRUCTION");
        when(skills.recommendationSkills(eq(ACTOR), anyList())).thenReturn(Map.of("a",List.of(ready)),Map.of("a",List.of(upgraded)));
        assertEquals(409,assertThrows(Failure.class,()->service.recommend(ACTOR,"task",new Request("7"))).status());
    }


    @Test void externalInstalledSkillIsSentToModelAndOnlyPublicSummaryIsReturned() throws Exception {
        var agents=mock(AgentService.class);var skills=mock(SkillManagementService.class);var model=mock(AgentAssigneeRecommendationModel.class);
        var a=new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("external","External",null,false,"online"),List.of(),"private-owner");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7"));when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(a));
        var fact=new SkillManagementService.RecommendationSkill("private-installation","private-key","private-version","甜点制作","制作蛋糕和甜点",true,"NOT_EXECUTABLE","INSTRUCTION");
        when(skills.recommendationSkills(ACTOR,List.of(a))).thenReturn(Map.of("external",List.of(fact)));
        when(model.judge(anyString(),anyString())).thenReturn(ANSWER);
        var result=new AssigneeRecommendationService(agents,skills,model,mock(RecommendationMaterials.class)).recommend(ACTOR,"task",new Request("7"));
        assertEquals("READY",result.state());assertFalse(result.recommendations().getFirst().agent().boundToMe());
        assertEquals("甜点制作",result.recommendations().getFirst().evidence().getFirst().label());
        var captor=org.mockito.ArgumentCaptor.forClass(String.class);verify(model).judge(captor.capture(),anyString());
        String input=captor.getValue();assertTrue(input.contains("制作蛋糕和甜点"));assertFalse(input.contains("技能信息未公开"));
        String output=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        for(String secret:List.of("private-owner","private-installation","private-key","private-version")) {
            assertFalse(input.contains(secret));assertFalse(output.contains(secret));
        }
        assertTrue(output.contains("public-skill:1"));
        verify(skills,times(2)).recommendationSkills(ACTOR,List.of(a));verify(agents,never()).assignTask(anyString(),any());
    }
    @Test void externalPolicyWithdrawalOrOwnerChangeInvalidatesRecommendation() {
        var agents=mock(AgentService.class);var skills=mock(SkillManagementService.class);var model=mock(AgentAssigneeRecommendationModel.class);
        var a=new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("external","External",null,false,"online"),List.of(),"provider");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task("7"));when(model.judge(anyString(),anyString())).thenReturn(ANSWER);
        var service=new AssigneeRecommendationService(agents,skills,model,mock(RecommendationMaterials.class));
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(a),List.of());
        assertEquals(409,assertThrows(Failure.class,()->service.recommend(ACTOR,"task",new Request("7"))).status());
        var rebound=new AgentTaskAssigneeCandidateDTO(a.target(),a.abilities(),"new-provider");
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(a),List.of(rebound));
        assertEquals(409,assertThrows(Failure.class,()->service.recommend(ACTOR,"task",new Request("7"))).status());
    }
}
