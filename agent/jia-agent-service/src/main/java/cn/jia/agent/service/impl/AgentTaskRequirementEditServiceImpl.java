package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.*;
import cn.jia.task.service.TaskService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/** Root lock serializes edits with assignment/cancellation. All writes share its transaction. */
@Named
public class AgentTaskRequirementEditServiceImpl implements AgentTaskRequirementEditService {
    private final AgentTaskMutationTransaction transactions;
    private final AgentTaskRequirementSnapshotService requirements;
    private final PersonalWorkspaceTaskLinkService links;
    private final AgentTaskMetaDao tasks;
    private final org.springframework.beans.factory.ObjectProvider<TaskService> plans;
    private final JdbcTemplate jdbc;

    @Inject
    public AgentTaskRequirementEditServiceImpl(AgentTaskMutationTransaction transactions,
            AgentTaskRequirementSnapshotService requirements, PersonalWorkspaceTaskLinkService links,
            AgentTaskMetaDao tasks, org.springframework.beans.factory.ObjectProvider<TaskService> plans, JdbcTemplate jdbc) {
        this.transactions=transactions; this.requirements=requirements; this.links=links;
        this.tasks=tasks; this.plans=plans; this.jdbc=jdbc;
    }

    @Override public View read(AgentTaskExecutionGrantService.Scope scope, String taskId) {
        return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> {
                    editable(scope,taskId,root);
                    return view(requirements.readCurrent(scope,taskId), selections(list(scope,taskId)));
                });
    }

    @Override public View save(AgentTaskExecutionGrantService.Scope scope,String taskId,
            String key,Command command) {
        validate(key,command);
        return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> {
                    editable(scope,taskId,root);
                    var current=requirements.readCurrent(scope,taskId);
                    var existing=list(scope,taskId);
                    String requestHash=requestHash(command);
                    var priorOperation=operation(scope,taskId,key);
                    if (priorOperation!=null) {
                        if (!requestHash.equals(priorOperation.hash())
                                || root.getTaskVersion()!=priorOperation.taskVersion()
                                || current.requirementRevision()!=priorOperation.revision()
                                || !priorOperation.resultHash().equals(resultHash(current.title(),current.description(),selections(existing)))) throw failure(Reason.CONFLICT);
                        return view(current,selections(existing));
                    }
                    if (root.getTaskVersion()!=command.expectedTaskVersion()
                            || current.requirementRevision()!=command.expectedRequirementRevision())
                        throw failure(Reason.CONFLICT);
                    if (Objects.equals(current.title(),command.title())
                            && Objects.equals(current.description(),command.description())
                            && new HashSet<>(selections(existing)).equals(new HashSet<>(command.attachments()))) {
                        recordOperation(scope,taskId,key,requestHash,resultHash(command.title(),command.description(),command.attachments()),root.getTaskVersion(),current.requirementRevision());
                        return view(current,selections(existing));
                    }
                    requirements.updateCurrent(scope,taskId,current.requirementRevision(),command.title(),command.description());
                    var linkScope=new PersonalWorkspaceTaskLinkService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
                    var selected=new HashSet<>(command.attachments());
                    for (var link:existing) if (!selected.contains(new Attachment(link.fileId(),link.version()))) {
                        links.detach(linkScope,taskId,link.relationId(),PersonalWorkspaceTaskLinkServiceImpl.etag(link),
                                "edit-remove:"+UUID.nameUUIDFromBytes((key+":"+link.relationId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                    var prior=new HashSet<>(selections(existing));
                    int index=0;
                    for (var attachment:command.attachments()) if (!prior.contains(attachment)) {
                        links.create(linkScope,taskId,new PersonalWorkspaceTaskLinkService.CreateCommand(
                                attachment.fileId(),attachment.version(),"INPUT",key+":add:"+(index++)));
                    }
                    // Existing task-plan columns are deliberately short summaries, not the source.
                    var planService=plans.getIfAvailable();
                    if (planService==null || !planService.updateSummaryInOwnerScope(scope.tenantId(),scope.clientId(),
                            scope.ownerJiacn(),Long.valueOf(taskId),summary(command.title(),30),summary(command.description(),200)))
                        throw failure(Reason.UNAVAILABLE);
                    root.setTaskVersion(command.expectedTaskVersion()+1);
                    root.setUpdateTime(System.currentTimeMillis());
                    if (tasks.updateById(root)!=1) throw failure(Reason.UNAVAILABLE);
                    recordOperation(scope,taskId,key,requestHash,resultHash(command.title(),command.description(),command.attachments()),root.getTaskVersion(),command.expectedRequirementRevision()+1);
                    return new View(taskId,Long.toString(root.getTaskVersion()),
                            Long.toString(command.expectedRequirementRevision()+1),command.title(),
                            command.description(),List.copyOf(command.attachments()));
                });
    }

    @Override public View readSaved(AgentTaskExecutionGrantService.Scope scope,String taskId,String key) {
        validKey(key);
        return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,root -> {
            editable(scope,taskId,root);
            var committed=operation(scope,taskId,key);
            if (committed==null) throw failure(Reason.NOT_FOUND);
            var current=requirements.readCurrent(scope,taskId);
            if (root.getTaskVersion()!=committed.taskVersion() || current.requirementRevision()!=committed.revision())
                throw failure(Reason.CONFLICT);
            var attachments=selections(list(scope,taskId));
            if (!committed.resultHash().equals(resultHash(current.title(),current.description(),attachments))) throw failure(Reason.CONFLICT);
            return view(current,attachments);
        });
    }
    private record Operation(String hash,String resultHash,long taskVersion,long revision) { }
    private Operation operation(AgentTaskExecutionGrantService.Scope scope,String taskId,String key) {
        var rows=jdbc.query("SELECT request_sha256,result_sha256,task_version,requirement_revision FROM agent_task_requirement_edit_operation "
                + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND operation_key=?",
                (r,n)->new Operation(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4)),
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,key);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void recordOperation(AgentTaskExecutionGrantService.Scope scope,String taskId,String key,String hash,String resultHash,long taskVersion,long revision) {
        if (jdbc.update("INSERT INTO agent_task_requirement_edit_operation "
                + "(tenant_id,client_id,owner_jiacn,task_id,operation_key,request_sha256,result_sha256,task_version,requirement_revision) VALUES (?,?,?,?,?,?,?,?,?)",
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,key,hash,resultHash,taskVersion,revision)!=1) throw failure(Reason.UNAVAILABLE);
    }
    private static String resultHash(String title,String description,List<Attachment> attachments) {
        return requestHash(new Command(0,1,title,description,attachments));
    }
    private static String requestHash(Command command) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var fields=new ArrayList<String>();
            fields.add("MATTER_EDIT_V1"); fields.add(Long.toString(command.expectedTaskVersion()));
            fields.add(Long.toString(command.expectedRequirementRevision()));fields.add(command.title());fields.add(command.description());
            command.attachments().stream().sorted(Comparator.comparing(Attachment::fileId).thenComparingInt(Attachment::version))
                    .forEach(a -> {fields.add(a.fileId());fields.add(Integer.toString(a.version()));});
            for (String field:fields) {
                byte[] bytes=field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) {throw new IllegalStateException(unavailable);}
    }
    private static void validKey(String key) {
        if (key==null || !key.matches("[A-Za-z0-9_-]{1,80}") || "CREATE".equals(key)) throw failure(Reason.BAD_REQUEST);
    }
    private void editable(AgentTaskExecutionGrantService.Scope scope,String taskId,AgentTaskMetaEntity root) {
        if (root==null) throw failure(Reason.NOT_FOUND);
        if (!"open".equals(root.getRewardStatus()) || root.getAssignedAgentId()!=null
                && !root.getAssignedAgentId().isBlank() || root.getAssignedAt()!=null || root.getStartedAt()!=null)
            throw failure(Reason.NOT_EDITABLE);
        // Funding is a distinct authority. The task root is already locked before this guard.
        Integer funded=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_funding WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=?",
                Integer.class,scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
        if (funded==null || funded!=0) throw failure(Reason.NOT_EDITABLE);
    }
    private List<PersonalWorkspaceTaskLinkService.LinkView> list(AgentTaskExecutionGrantService.Scope scope,String taskId) {
        var all=new ArrayList<PersonalWorkspaceTaskLinkService.LinkView>();
        var linkScope=new PersonalWorkspaceTaskLinkService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        String cursor=null;
        do {
            var page=links.list(linkScope,taskId,cursor);
            for (var item:page.items()) if ("ACTIVE".equals(item.state()) && Set.of("INPUT","REFERENCE").contains(item.role())) all.add(item);
            cursor=page.nextCursor();
        } while (cursor!=null);
        return all;
    }
    private static List<Attachment> selections(List<PersonalWorkspaceTaskLinkService.LinkView> links) {
        return links.stream().map(item -> new Attachment(item.fileId(),item.version())).distinct().toList();
    }
    private static View view(AgentTaskRequirementSnapshotService.CurrentSnapshot current,List<Attachment> attachments) {
        return new View(current.taskId(),Long.toString(current.taskVersion()),Long.toString(current.requirementRevision()),
                current.title(),current.description(),attachments);
    }
    private static void validate(String key,Command command) {
        validKey(key);
        if (command==null || command.expectedTaskVersion()<0 || command.expectedTaskVersion()==Long.MAX_VALUE
                || command.expectedRequirementRevision()<1 || command.expectedRequirementRevision()==Long.MAX_VALUE
                || command.title()==null || command.title().isBlank() || command.title().length()>200
                || command.description()==null || command.description().isBlank() || command.description().length()>20000
                || command.attachments()==null || command.attachments().size()>32
                || new HashSet<>(command.attachments()).size()!=command.attachments().size()) throw failure(Reason.BAD_REQUEST);
        AgentTaskRequirementSnapshotServiceImpl.validateOriginalInput(command.title(),command.description());
        for (var a:command.attachments()) if (a==null || a.fileId()==null || a.fileId().isBlank()
                || a.fileId().length()>100 || !a.fileId().equals(a.fileId().strip()) || a.version()<1)
            throw failure(Reason.BAD_REQUEST);
    }
    private static String summary(String value,int max) {
        return value==null ? null : value.substring(0,value.offsetByCodePoints(0,Math.min(value.codePointCount(0,value.length()),max)));
    }
    private static Failure failure(Reason reason) { return new Failure(reason); }
}
