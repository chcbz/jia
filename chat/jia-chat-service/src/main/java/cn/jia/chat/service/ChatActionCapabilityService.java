package cn.jia.chat.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Current adapter facts only: this projection never grants authority or invokes a tool. */
@Service
public final class ChatActionCapabilityService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ChatActionCapabilityService.class);
    public record Scope(String tenantId, String ownerJiacn, String clientId, String targetAgentId) { }
    private static final com.fasterxml.jackson.databind.ObjectMapper SKILL_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private cn.jia.agent.service.InstalledSkillDescriptorLookup installedSkills;
    private cn.jia.agent.service.SkillCapabilityLookup skillRuntimes;
    @org.springframework.beans.factory.annotation.Autowired
    public void setInstalledSkillCapabilities(cn.jia.agent.service.InstalledSkillDescriptorLookup installed,
            cn.jia.agent.service.SkillCapabilityLookup runtimes) {
        installedSkills = Objects.requireNonNull(installed); skillRuntimes = Objects.requireNonNull(runtimes);
    }

    private cn.jia.agent.service.PersonalWorkspaceExecutionService nativeExecutions;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setNativeExecutions(cn.jia.agent.service.PersonalWorkspaceExecutionService value) { nativeExecutions=value; }

    /** Installation/runtime observation, NOT availableActions or a task authorization grant.
     * I16/I18 must separately re-admit the selected skill under the current task/assignment. */
    public record SkillCandidate(String skillKey, String skillVersion, String title,
            String state, String reasonCode, String metadataJson, boolean installationVerified) { }
    public record InstalledCatalog(String catalogRevision, List<SkillCandidate> items) {
        public InstalledCatalog { items = List.copyOf(items); }
    }
    public InstalledCatalog inspectInstalledSkills(Scope scope,
            cn.jia.agent.service.AgentManagedSessionLookup.SessionFence expectedFence) {
        cn.jia.agent.service.SkillCapabilityLookup.validateFence(expectedFence);
        return observeInstalledSkills(scope, expectedFence);
    }

    /** Owner-only profile observation without a trusted live runtime; never creates a fence. */
    public InstalledCatalog inspectInstalledSkillsWithoutRuntime(Scope scope) {
        return observeInstalledSkills(scope, null);
    }

    private InstalledCatalog observeInstalledSkills(Scope scope,
            cn.jia.agent.service.AgentManagedSessionLookup.SessionFence expectedFence) {
        Objects.requireNonNull(scope);
        if(installedSkills==null||(expectedFence!=null&&skillRuntimes==null))
            throw new cn.jia.agent.service.InstalledSkillDescriptorLookup.SourceUnavailable();
        var key=new cn.jia.agent.service.SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),scope.targetAgentId());
        var descriptors=installedSkills.readCurrent(key);
        var runtime=expectedFence==null?null:Objects.requireNonNull(skillRuntimes.lookup(key,expectedFence));
        var results=new ArrayList<SkillCandidate>();
        var counts=new java.util.HashMap<List<String>,Integer>();
        for(var descriptor:descriptors.skills())
            counts.merge(List.of(descriptor.skillKey(),descriptor.skillVersion()),1,Integer::sum);
        for(var descriptor:descriptors.skills()) {
            String reason=descriptor.reasonCode();
            String title=descriptor.skillKey(); String metadata=null;
            String runner=null; boolean requiresBinding=false;
            if(descriptor.verified()) {
                try {
                    var manifest=SKILL_JSON.readTree(descriptor.manifestJson());
                    title=manifest.path("title").asText(descriptor.skillKey()); runner=manifest.path("runner").asText();
                    requiresBinding="provider-operation".equals(runner)||manifest.path("requirements").has("providerAlias");
                    // Allowlist: no entrypoint, installation, host, binding or internal package paths.
                    var publicMetadata=SKILL_JSON.createObjectNode();
                    for(String field:List.of("description","parametersSchema","inputs","outputs","costInfo"))
                        publicMetadata.set(field,manifest.get(field));
                    metadata=publicMetadata.toString();
                } catch(java.io.IOException invalid) {
                    throw new cn.jia.agent.service.InstalledSkillDescriptorLookup.SourceUnavailable(invalid);
                }
            } else if(reason==null) reason="SKILL_INSTALLATION_EVIDENCE_INVALID";
            if(counts.get(List.of(descriptor.skillKey(),descriptor.skillVersion()))!=1)
                reason="SKILL_INSTALLATION_AMBIGUOUS";
            if(reason==null && runtime==null) reason="SKILL_RUNTIME_UNAVAILABLE";
            if(reason==null && runtime.state()!=cn.jia.agent.service.SkillCapabilityLookup.State.AVAILABLE)
                reason="SKILL_RUNTIME_"+runtime.state().name();
            if(reason==null) {
                var reports=runtime.catalog().skills().stream().filter(r->descriptor.skillKey().equals(r.skillKey())
                        && descriptor.skillVersion().equals(r.skillVersion())).toList();
                if(reports.size()!=1) reason="SKILL_RUNTIME_NOT_DECLARED";
                else {
                    var report=reports.getFirst();
                    if(!Objects.equals(descriptor.runtimeInstallationId(),runtime.fence().runtimeInstallationId())
                            || !Objects.equals(descriptor.runtimeHostId(),runtime.fence().hostId())
                            || !Objects.equals(descriptor.packageSha256(),report.packageSha256())
                            || !Objects.equals(descriptor.manifestSha256(),report.manifestSha256())
                            || !Objects.equals(runner,report.runner())) reason="SKILL_RUNTIME_BINDING_MISMATCH";
                    else if(report.readiness()!=cn.jia.agent.service.SkillCapabilityLookup.Readiness.READY)
                        reason=report.reasonCode()==null?"SKILL_RUNTIME_UNAVAILABLE":report.reasonCode();
                    else if(report.reasonCode()!=null||report.toolchainDigest()==null
                            || (requiresBinding&&report.bindingRevision()==null))
                        reason="SKILL_RUNTIME_EVIDENCE_INVALID";
                }
            }
            results.add(new SkillCandidate(descriptor.skillKey(),descriptor.skillVersion(),title,
                    reason==null?"RUNTIME_READY":"UNAVAILABLE",reason,metadata,
                    descriptor.verified()&&counts.get(List.of(descriptor.skillKey(),descriptor.skillVersion()))==1));
        }
        // Fence/live directory is rechecked after the join. No old READY survives a reconnect.
        if(runtime!=null&&runtime.state()==cn.jia.agent.service.SkillCapabilityLookup.State.AVAILABLE
                && !runtime.equals(skillRuntimes.lookup(key,expectedFence))) {
            results.replaceAll(item->new SkillCandidate(item.skillKey(),item.skillVersion(),item.title(),
                    "UNAVAILABLE","SKILL_RUNTIME_CHANGED",item.metadataJson(),item.installationVerified()));
            return new InstalledCatalog(null,results);
        }
        return new InstalledCatalog(runtime==null||runtime.catalog()==null?null:runtime.catalog().catalogRevision(),results);
    }

    private final cn.jia.agent.service.TaskSkillPolicyService policies;
    public ChatActionCapabilityService(cn.jia.agent.service.InstalledSkillDescriptorLookup installed,
            cn.jia.agent.service.SkillCapabilityLookup runtimes, cn.jia.agent.service.TaskSkillPolicyService policies) {
        setInstalledSkillCapabilities(installed, runtimes); this.policies=Objects.requireNonNull(policies);
    }
    public record TaskCatalog(String catalogRevision, List<Map<String,Object>> items) {
        public TaskCatalog { items=List.copyOf(items); }
    }
    /** Read-only policy AND installation AND live authenticated fence. Costs never decide callability. */
    public TaskCatalog taskCatalog(Scope scope, String taskId) {
        var key=new cn.jia.agent.service.SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.targetAgentId());
        var policy=policies.read(key,taskId);
        return taskCatalog(scope,taskId,policy);
    }
    private TaskCatalog taskCatalog(Scope scope,String taskId,cn.jia.agent.service.TaskSkillPolicyService.Snapshot policy) {
        var key=new cn.jia.agent.service.SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.targetAgentId());
        // No assignment/grant means no right to enumerate another Agent's installed packages.
        if(policy.runtimeScope()==null) {
            LOG.info("Task skill catalog denied: taskId={} agentId={} reason={}",
                    taskId, scope.targetAgentId(), policy.reasonCode());
            return new TaskCatalog(null,List.of());
        }
        var runtimeKey=policy.runtimeScope();
        if(!scope.tenantId().equals(runtimeKey.tenantId())||!scope.clientId().equals(runtimeKey.clientId())
                ||!scope.targetAgentId().equals(runtimeKey.agentId()))
            throw new cn.jia.agent.service.TaskSkillPolicyService.Failure(cn.jia.agent.service.TaskSkillPolicyService.Failure.Reason.INTEGRITY_ERROR);
        var runtimeScope=new Scope(runtimeKey.tenantId(),runtimeKey.ownerJiacn(),runtimeKey.clientId(),runtimeKey.agentId());
        InstalledCatalog observed;
        if(policy.fence()!=null) observed=inspectInstalledSkills(runtimeScope,policy.fence());
        else {
            var list=new ArrayList<SkillCandidate>();
            for(var d:installedSkills.readCurrent(runtimeKey).skills()) {
                String title=d.skillKey(),metadata=null;
                if(d.verified()) try {
                    var m=SKILL_JSON.readTree(d.manifestJson());title=m.path("title").asText(d.skillKey());
                    var publicMetadata=SKILL_JSON.createObjectNode();
                    for(String field:List.of("description","parametersSchema","inputs","outputs","costInfo")) publicMetadata.set(field,m.get(field));
                    metadata=publicMetadata.toString();
                } catch(java.io.IOException malformed) {throw new cn.jia.agent.service.InstalledSkillDescriptorLookup.SourceUnavailable(malformed);}
                list.add(new SkillCandidate(d.skillKey(),d.skillVersion(),title,"UNAVAILABLE",
                        d.reasonCode()!=null?d.reasonCode():"SKILL_RUNTIME_UNAVAILABLE",metadata,d.verified()));
            }
            observed=new InstalledCatalog(null,list);
        }
        if (observed.items().isEmpty()) LOG.info("Task skill catalog has no installation descriptors: taskId={} agentId={} catalogPresent={}",
                taskId, scope.targetAgentId(), observed.catalogRevision()!=null);
        var items=new ArrayList<Map<String,Object>>();
        boolean stable=policy.equals(policies.read(key,taskId));
        for(var item:observed.items()) {
            var view=new java.util.LinkedHashMap<String,Object>();
            view.put("skillKey",item.skillKey());view.put("skillVersion",item.skillVersion());view.put("title",item.title());
            boolean callable=stable&&policy.decision()==cn.jia.agent.service.TaskSkillPolicyService.Decision.ALLOWED&&"RUNTIME_READY".equals(item.state());
            view.put("state",callable?"CALLABLE":"UNAVAILABLE");
            view.put("reasonCode",!stable?"SKILL_TASK_POLICY_CHANGED":policy.decision()!=cn.jia.agent.service.TaskSkillPolicyService.Decision.ALLOWED?policy.reasonCode():item.reasonCode());
            view.put("costInfo",costInfo(item.metadataJson()));
            items.add(java.util.Collections.unmodifiableMap(view));
        }
        return new TaskCatalog(observed.catalogRevision(),items);
    }
    public List<Map<String,Object>> available(Scope scope, String taskId, List<Map<String,Object>> catalog) {
        var key=new cn.jia.agent.service.SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.targetAgentId());
        var policy=policies.read(key,taskId);
        if(policy.decision()!=cn.jia.agent.service.TaskSkillPolicyService.Decision.ALLOWED||policy.runtimeScope()==null)return List.of();
        var task=taskCatalog(scope,taskId,policy);
        var runtimeKey=policy.runtimeScope();
        var installed=installedSkills.readCurrent(runtimeKey);
        if(!policy.equals(policies.read(key,taskId))||!installed.equals(installedSkills.readCurrent(runtimeKey)))return List.of();
        var callable=task.items().stream().filter(x->"CALLABLE".equals(x.get("state"))).toList();
        var actions=new ArrayList<Map<String,Object>>();
        for(var item:callable) {
            var matches=installed.skills().stream().filter(d->d.verified()&&d.skillKey().equals(item.get("skillKey"))
                    &&d.skillVersion().equals(item.get("skillVersion"))).toList();
            if(matches.size()!=1)continue;
            var d=matches.getFirst();
            try {
                var manifest=SKILL_JSON.readTree(d.manifestJson());
                var skill=Map.of("skillKey",d.skillKey(),"skillVersion",d.skillVersion(),"packageSha256",d.packageSha256(),"manifestSha256",d.manifestSha256());
                var action=new java.util.LinkedHashMap<String,Object>();
                action.put("actionId","act_"+cn.jia.chat.api.ChatBountyInteractionV3Wire.shaText(CanonicalContextJson.write(skill)));
                action.put("kind","EXECUTE");action.put("operation","EXECUTE_SKILL");action.put("skill",skill);
                action.put("parametersSchema",SKILL_JSON.convertValue(manifest.get("parametersSchema"),Map.class));
                action.put("inputMimeTypes",SKILL_JSON.convertValue(manifest.at("/inputs/acceptedMimeTypes"),List.class));
                action.put("minSources",manifest.at("/inputs/minItems").intValue());action.put("maxSources",manifest.at("/inputs/maxItems").intValue());
                action.put("outputMimeTypes",SKILL_JSON.convertValue(manifest.at("/outputs/mimeTypes"),List.class));
                actions.add(java.util.Collections.unmodifiableMap(action));
            }catch(java.io.IOException malformed){throw new cn.jia.agent.service.InstalledSkillDescriptorLookup.SourceUnavailable(malformed);}
        }
        return List.copyOf(actions);
    }
    /** Advertise only after authenticated runtime declaration and server execution configuration agree. */
    public List<Map<String,Object>> availableNative(Scope scope,String taskId,List<Map<String,Object>> catalog,Map<String,Object> declaration,String sessionId) {
        var actions=new ArrayList<>(available(scope,taskId,catalog));
        int legacyCount=actions.size();
        if(declaration==null||!(declaration.get("nativeExecution") instanceof Map<?,?> nativeDeclaration)||nativeExecutions==null)return List.copyOf(actions);
        var server=nativeExecutions.nativeLocalFileCapabilities();if(server==null||!server.generationEnabled())return List.copyOf(actions);
        var key=new cn.jia.agent.service.SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.targetAgentId());
        var policy=policies.read(key,taskId);
        if(policy.decision()!=cn.jia.agent.service.TaskSkillPolicyService.Decision.ALLOWED||policy.fence()==null||policy.runtimeScope()==null)return List.copyOf(actions);
        var live=skillRuntimes.lookup(policy.runtimeScope(),policy.fence());
        if(live==null||live.state()!=cn.jia.agent.service.SkillCapabilityLookup.State.AVAILABLE||!java.util.Objects.equals(live.sessionId(),sessionId))return List.copyOf(actions);
        if(!(nativeDeclaration.get("outputMimeTypes") instanceof List<?> runtimeMimes))return List.copyOf(actions);
        // The authenticated runtime owns tool capability. The API does not keep
        // a second format catalogue; task/session authorization remains above.
        var allowed=runtimeMimes.stream().map(String.class::cast).distinct().sorted().toList();
        if(allowed.isEmpty())return List.copyOf(actions);
        var input=catalog.stream().map(x->x.get("contentMimeType")).filter(String.class::isInstance).map(String.class::cast).distinct().sorted().toList();
        var schema=Map.<String,Object>of("$schema","https://json-schema.org/draft/2020-12/schema","type","object","additionalProperties",false,"properties",Map.of());
        var action=new java.util.LinkedHashMap<String,Object>();
        action.put("actionId","act_"+cn.jia.chat.api.ChatBountyInteractionV3Wire.shaText(CanonicalContextJson.write(Map.of("operation","EXECUTE_NATIVE","inputMimeTypes",input,"outputMimeTypes",allowed))));
        action.put("kind","EXECUTE");action.put("operation","EXECUTE_NATIVE");action.put("skill",null);action.put("parametersSchema",schema);
        action.put("inputMimeTypes",input);action.put("minSources",0);action.put("maxSources",catalog.size());action.put("outputMimeTypes",allowed);
        if(!policy.equals(policies.read(key,taskId))||!live.equals(skillRuntimes.lookup(policy.runtimeScope(),policy.fence())))return List.copyOf(actions);
        actions.add(java.util.Collections.unmodifiableMap(action));
        if(!(nativeDeclaration.get("supportedToolPolicyRefs") instanceof List<?> refs)||!refs.contains(cn.jia.agent.service.SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY))return List.copyOf(actions);
        var current=installedSkills.readCurrent(policy.runtimeScope());
        for(var d:current.skills()) {
            if(current.skills().stream().filter(x->Objects.equals(x.installationId(),d.installationId())).count()!=1)continue;
            if(!d.verifiedNative()||d.manifestJson()!=null||d.manifestSha256()!=null
                    ||!Objects.equals(d.runtimeInstallationId(),policy.fence().runtimeInstallationId())||!Objects.equals(d.runtimeHostId(),policy.fence().hostId()))continue;
            var matches=live.catalog().skills().stream().filter(r->Objects.equals(r.skillInstallationId(),d.installationId())
                    &&Objects.equals(r.skillKey(),d.skillKey())&&Objects.equals(r.skillVersion(),d.skillVersion())
                    &&Objects.equals(r.packageSha256(),d.packageSha256())&&r.manifestSha256()==null&&"INSTRUCTION".equals(r.kind())&&r.enabled()).toList();
            if(matches.size()!=1)continue;
            var skill=new java.util.LinkedHashMap<String,Object>();skill.put("skillKey",d.skillKey());skill.put("skillVersion",d.skillVersion());
            skill.put("packageSha256",d.packageSha256());skill.put("manifestSha256",null);
            var selected=new java.util.LinkedHashMap<String,Object>(action);selected.put("actionId",nativeSkillActionId(d,input,allowed));selected.put("skill",skill);
            actions.add(java.util.Collections.unmodifiableMap(selected));
        }
        if(!current.equals(installedSkills.readCurrent(policy.runtimeScope()))||!policy.equals(policies.read(key,taskId))
                ||!live.equals(skillRuntimes.lookup(policy.runtimeScope(),policy.fence())))return List.copyOf(actions.subList(0,legacyCount));
        return List.copyOf(actions);
    }
    static String nativeSkillActionId(cn.jia.agent.service.InstalledSkillDescriptorLookup.Descriptor d,List<String> input,List<String> output) {
        var identity=new java.util.LinkedHashMap<String,Object>();identity.put("operation","EXECUTE_NATIVE");identity.put("installationId",d.installationId());
        identity.put("skillKey",d.skillKey());identity.put("skillVersion",d.skillVersion());identity.put("packageSha256",d.packageSha256());
        identity.put("manifestSha256",d.manifestSha256());identity.put("toolPolicyRef",cn.jia.agent.service.SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY);
        identity.put("inputMimeTypes",input);identity.put("outputMimeTypes",output);
        return "act_"+cn.jia.chat.api.ChatBountyInteractionV3Wire.shaText(CanonicalContextJson.write(identity));
    }
    private static Object costInfo(String metadata) {
        try {
            if(metadata!=null) {
                var raw=SKILL_JSON.readTree(metadata).get("costInfo");
                if(raw!=null&&raw.isObject()&&Set.of("FREE","FIXED","METERED","UNKNOWN").contains(raw.path("mode").asText()))
                    return SKILL_JSON.convertValue(raw,Map.class);
            }
        }catch(java.io.IOException ignored){/* metadata warning only, never authority */}
        return Map.of("mode","UNKNOWN");
    }
}
