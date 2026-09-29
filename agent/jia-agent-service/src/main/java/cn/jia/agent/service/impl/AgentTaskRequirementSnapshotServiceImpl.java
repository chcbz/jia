package cn.jia.agent.service.impl;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.core.context.EsContextHolder;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Insert-only, source-exact history; no reconstruction from task_plan or task event projections. */
@Named
public final class AgentTaskRequirementSnapshotServiceImpl implements AgentTaskRequirementSnapshotService {
    private final JdbcTemplate jdbc;
    private final AgentTaskMutationTransaction transactions;

    @Inject
    public AgentTaskRequirementSnapshotServiceImpl(JdbcTemplate jdbc, AgentTaskMutationTransaction transactions) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.transactions=Objects.requireNonNull(transactions);
    }

    @Override public Snapshot captureOnCreate(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String title, String description) {
        validScope(scope,taskId);
        writeTransaction();
        if (latest(scope,taskId)!=null) throw new IllegalStateException("Requirement snapshot already exists");
        return insert(scope,taskId,1,0,"CREATE",title,description,"CREATE");
    }

    @Override public Snapshot reconfirm(AgentTaskExecutionGrantService.Scope scope, String taskId,
            long expectedTaskVersion, String confirmationId, String title, String description) {
        validScope(scope,taskId);
        var principal=SecurityContextHolder.getContext().getAuthentication();
        var context=EsContextHolder.getContext();
        if (context==null || !scope.ownerJiacn().equals(context.getJiacn())
                || !scope.clientId().equals(context.getClientId())
                || !(principal instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || !scope.ownerJiacn().equals(jwt.getToken().getClaims().get("jiacn"))
                || !scope.clientId().equals(jwt.getToken().getClaims().get("client_id")))
            throw new IllegalArgumentException("Authenticated task owner required for re-confirmation");
        if (expectedTaskVersion<0 || confirmationId==null || !confirmationId.matches("[A-Za-z0-9_-]{1,100}")
                || "CREATE".equals(confirmationId))
            throw new IllegalArgumentException("Confirmation identity/version invalid");
        // Lock order: task root -> funding guard -> latest snapshot -> append. No grants/outbox locks.
        return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> {
                    writeTransaction();
                    Snapshot previous=latest(scope,taskId);
                    List<Snapshot> repeated=jdbc.query("SELECT tenant_id,client_id,owner_jiacn,task_id,revision,title,description,"
                                    + "content_sha256,source FROM agent_task_requirement_snapshot WHERE tenant_id=? "
                                    + "AND client_id=? AND owner_jiacn=? AND task_id=? AND confirmation_id=? "
                                    + "AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) "
                                    + "AND CAST(client_id AS BINARY)=CAST(? AS BINARY) "
                                    + "AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY) "
                                    + "AND CAST(task_id AS BINARY)=CAST(? AS BINARY) "
                                    + "AND CAST(confirmation_id AS BINARY)=CAST(? AS BINARY) LIMIT 2",
                            (rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                                    rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)),
                            scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,confirmationId,
                            scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,confirmationId);
                    if (!repeated.isEmpty()) {
                        if (repeated.size()!=1) throw new IllegalStateException("Ambiguous confirmation");
                        Snapshot prior=verified(repeated.getFirst(),scope,taskId);
                        Long originalVersion=jdbc.queryForObject("SELECT task_version_at_confirmation "
                                + "FROM agent_task_requirement_snapshot WHERE tenant_id=? AND client_id=? "
                                + "AND owner_jiacn=? AND task_id=? AND confirmation_id=? AND revision=?",
                                Long.class,scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                                taskId,confirmationId,prior.revision());
                        if (originalVersion==null || originalVersion!=expectedTaskVersion
                                || previous==null || prior.revision()!=previous.revision()
                                || !Objects.equals(prior.title(),title)
                                || !Objects.equals(prior.description(),description))
                            throw new IllegalStateException("Confirmation idempotency conflict");
                        return prior;
                    }
                    if (root.getTaskVersion()==null || root.getTaskVersion()!=expectedTaskVersion)
                        throw new IllegalStateException("Task root version changed during confirmation");
                    Integer funded=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_funding "
                            + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? "
                            + "AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) "
                            + "AND CAST(client_id AS BINARY)=CAST(? AS BINARY) "
                            + "AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY) "
                            + "AND CAST(task_id AS BINARY)=CAST(? AS BINARY)", Integer.class,
                            scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,
                            scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
                    if (funded==null || funded!=0) throw new IllegalStateException("Funded task confirmation is not authorized");
                    if (previous!=null && previous.revision()==Long.MAX_VALUE)
                        throw new IllegalStateException("Requirement revision exhausted");
                    return insert(scope,taskId,previous==null?1:previous.revision()+1,
                            expectedTaskVersion,confirmationId,title,description,"RECONFIRM");
                });
    }

    @Override public Snapshot read(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision) {
        validScope(scope,taskId);
        if (revision<1) throw new IllegalArgumentException("requirement revision invalid");
        List<Snapshot> rows=jdbc.query("SELECT tenant_id,client_id,owner_jiacn,task_id,revision,title,description,"
                        + "content_sha256,source FROM agent_task_requirement_snapshot "
                        + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND revision=? "
                        + "AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(client_id AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(task_id AS BINARY)=CAST(? AS BINARY) LIMIT 2",
                (rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)),
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,revision,
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
        if (rows.size()!=1) throw new IllegalStateException("Exact requirement snapshot unavailable");
        return verified(rows.getFirst(),scope,taskId);
    }

    @Override public Snapshot requireCurrent(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision) {
        if (revision<1) throw new IllegalArgumentException("requirement revision invalid");
        Snapshot current=latest(scope,taskId);
        if (current==null || current.revision()!=revision)
            throw new IllegalStateException("Current requirement revision not confirmed by owner");
        return current;
    }

    private Snapshot latest(AgentTaskExecutionGrantService.Scope scope,String taskId) {
        validScope(scope,taskId);
        List<Snapshot> rows=jdbc.query("SELECT tenant_id,client_id,owner_jiacn,task_id,revision,title,description,"
                        + "content_sha256,source FROM agent_task_requirement_snapshot "
                        + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? "
                        + "AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(client_id AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(task_id AS BINARY)=CAST(? AS BINARY) "
                        + "ORDER BY revision DESC LIMIT 1",
                (rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)),
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
        return rows.isEmpty()?null:verified(rows.getFirst(),scope,taskId);
    }

    /** Reject malformed UTF-16 and actual MEDIUMTEXT overflow before any task-plan insert. */
    static void validateOriginalInput(String title,String description) {
        if (title==null || title.isBlank()) throw new IllegalArgumentException("Confirmed title is missing");
        textBytes(title);
        if (description!=null) textBytes(description);
    }

    private Snapshot insert(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision,
            long taskVersion, String confirmationId, String title,String description,String source) {
        validateOriginalInput(title, description);
        String hash=digest(scope,taskId,revision,source,title,description);
        if (jdbc.update("INSERT INTO agent_task_requirement_snapshot "
                + "(tenant_id,client_id,owner_jiacn,task_id,revision,confirmation_id,task_version_at_confirmation,"
                + "title,description,content_sha256,source,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                taskId,revision,confirmationId,taskVersion,title,description,hash,source,System.currentTimeMillis())!=1)
            throw new IllegalStateException("Requirement snapshot insert failed");
        return new Snapshot(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,
                revision,title,description,hash,source);
    }

    private static Snapshot verified(Snapshot row,AgentTaskExecutionGrantService.Scope scope,String taskId) {
        if (!Objects.equals(row.tenantId(),scope.tenantId()) || !Objects.equals(row.clientId(),scope.clientId())
                || !Objects.equals(row.ownerJiacn(),scope.ownerJiacn()) || !Objects.equals(row.taskId(),taskId)
                || row.revision()<1 || row.title()==null || row.title().isBlank() || row.sha256()==null
                || !("CREATE".equals(row.source()) || "RECONFIRM".equals(row.source()))
                || !MessageDigest.isEqual(row.sha256().getBytes(StandardCharsets.US_ASCII),
                digest(scope,taskId,row.revision(),row.source(),row.title(),row.description()).getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalStateException("Requirement snapshot integrity/scope drift");
        return row;
    }

    private static String digest(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision,
            String source,String title,String description) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            for (String field:List.of("TASK_REQUIREMENT_SNAPSHOT_V1",scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),taskId,Long.toString(revision),source,title)) add(md,field);
            if (description==null) md.update((byte)0); else { md.update((byte)1); add(md,description); }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void add(MessageDigest md,String value) {
        byte[] bytes=textBytes(value);
        md.update(ByteBuffer.allocate(4).putInt(bytes.length).array());md.update(bytes);
    }
    private static byte[] textBytes(String value) {
        try {
            ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            if (encoded.remaining()>16_777_215) throw new IllegalArgumentException("Text exceeds MEDIUMTEXT bytes");
            byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid Unicode in requirement", invalid);
        }
    }
    private static void validScope(AgentTaskExecutionGrantService.Scope scope,String taskId) {
        if (scope==null || !"0".equals(scope.tenantId()) || !exact(scope.clientId(),50)
                || !exact(scope.ownerJiacn(),50) || "0".equals(scope.ownerJiacn())
                || !exact(taskId,100))
            throw new IllegalArgumentException("Owner-scoped requirement key invalid");
    }
    private static boolean exact(String value,int max) {
        return value!=null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0,value.length())<=max
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static void writeTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Requirement snapshot needs owning task-root transaction");
    }
}
