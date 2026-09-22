package cn.jia.agent.service.impl;

import cn.jia.agent.dao.HallReadDao;
import cn.jia.agent.entity.HallItemRow;
import cn.jia.agent.service.HallReadService;
import cn.jia.agent.service.HallPrivateMarkService;
import cn.jia.agent.state.AgentTaskStatus;
import org.springframework.beans.factory.annotation.Value;
import cn.jia.agent.service.HallRequestDraftService.OwnerScope;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** No writes, Provider calls, task-list enrichment or cross-source transaction/snapshot claims. */
@Named
public class HallReadServiceImpl implements HallReadService {
    private static final int PAGE_SIZE = 20; // same delivery page size as the existing draft read API
    private static final List<String> SOURCES = List.of("private", "task", "draft");
    private static final Set<String> PRIVATE_STATES = Set.of(
            "QUEUED", "OUTPUT_STAGED", "OUTPUT_COMMITTED", "FAILED", "INPUTS_REVOKED");
    private static final Set<String> TASK_STATES = Arrays.stream(AgentTaskStatus.values())
            .map(AgentTaskStatus::value).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final HallReadDao reads;
    private final LongSupplier clock;
    private final boolean formalEnabled;

    public HallReadServiceImpl(HallReadDao reads) { this(reads, System::currentTimeMillis, false); }
    @Inject public HallReadServiceImpl(HallReadDao reads,
            @Value("${jia.agent.formal-delivery.enabled:false}") boolean formalEnabled) {
        this(reads, System::currentTimeMillis, formalEnabled);
    }
    HallReadServiceImpl(HallReadDao reads, LongSupplier clock) { this(reads, clock, false); }
    HallReadServiceImpl(HallReadDao reads, LongSupplier clock, boolean formalEnabled) {
        this.reads = Objects.requireNonNull(reads); this.clock = Objects.requireNonNull(clock);
        this.formalEnabled = formalEnabled;
    }

    @Override public Overview overview(OwnerScope scope) {
        requireScope(scope);
        long asOf = clock.getAsLong();
        Section recent = section(scope, "all", "recent", "", null, asOf);
        Section needsAction = section(scope, "all", "needsAction", "", null, asOf);
        return new Overview(1, Map.of("recent", recent, "needsAction", needsAction),
                Map.of("recent", statuses(recent), "needsAction", statuses(needsAction)), asOf);
    }

    @Override public Items items(OwnerScope scope, String kind, String view, String q, String cursor) {
        requireScope(scope);
        kind = kind == null ? "all" : kind;
        view = view == null ? "recent" : view;
        q = q == null ? "" : q.strip();
        if (!("all".equals(kind) || SOURCES.contains(kind)) || !text(q, 200, true)) throw bad();
        if (!Set.of("recent", "needsAction", "archive").contains(view)) throw bad();
        Cursor before = null;
        if (cursor != null) {
            if ("all".equals(kind)) throw bad(); // only independent source continuation is defined
            before = decode(scope, kind, view, q, cursor);
        }
        long asOf = clock.getAsLong();
        Section section = section(scope, kind, view, q, before, asOf);
        return new Items(1, kind, view, q, section, statuses(section), asOf);
    }

    private Section section(OwnerScope scope, String kind, String view, String q,
            Cursor before, long asOf) {
        Map<String, Partition> partitions = new LinkedHashMap<>();
        for (String source : "all".equals(kind) ? SOURCES : List.of(kind)) {
            partitions.put(source, partition(scope, source, view, q, before, asOf));
        }
        boolean allError = partitions.values().stream().allMatch(p -> "error".equals(p.status()));
        boolean allComplete = partitions.values().stream().allMatch(p -> "complete".equals(p.status()));
        return new Section(allError ? "error" : allComplete ? "complete" : "partial",
                Map.copyOf(partitions));
    }

    private Partition partition(OwnerScope scope, String kind, String view, String q,
            Cursor before, long asOf) {
        if ("draft".equals(kind) && "archive".equals(view)) {
            return new Partition(List.of(), "error", null, null, "HALL_DRAFT_ARCHIVE_UNAVAILABLE");
        }
        try {
            List<HallItemRow> rows = reads.page(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                    kind, view, q, before == null ? null : before.updatedAt(),
                    before == null ? null : before.sourceType(), before == null ? null : before.id(),
                    PAGE_SIZE + 1);
            if (rows == null || rows.size() > PAGE_SIZE + 1) throw new IllegalStateException();
            List<ItemSummary> items = new ArrayList<>();
            Cursor previous = before;
            for (HallItemRow row : rows) {
                validate(scope, kind, view, row);
                Cursor current = new Cursor(row.getUpdatedAt(), row.getSourceType(), row.getSourceId());
                if (previous != null && compare(current, previous) >= 0) throw new IllegalStateException();
                previous = current;
                if (items.size() < PAGE_SIZE) items.add(summary(row, asOf));
            }
            String next = rows.size() <= PAGE_SIZE ? null : encode(scope, kind, view, q,
                    new Cursor(rows.get(PAGE_SIZE - 1).getUpdatedAt(),
                            rows.get(PAGE_SIZE - 1).getSourceType(), rows.get(PAGE_SIZE - 1).getSourceId()));
            String gap = "needsAction".equals(view) && "task".equals(kind) && !formalEnabled
                    ? "FORMAL_REVIEW_UNAVAILABLE" : null;
            return new Partition(List.copyOf(items), gap == null ? "complete" : "partial", next, null, gap);
        } catch (RuntimeException unavailable) {
            // A failed query or corrupt/foreign projection is NOT an empty successful source.
            // No exception text / SQL / identity details cross the browser boundary.
            return new Partition(List.of(), "error", null, null, "HALL_SOURCE_UNAVAILABLE");
        }
    }

    private static ItemSummary summary(HallItemRow row, long asOf) {
        String action = switch (row.getSourceType()) {
            case "DRAFT" -> "EDIT_DRAFT";
            case "PRIVATE_CASE" -> "OPEN_CASE";
            case "LEGACY_EXECUTION" -> "OPEN_EXECUTION";
            case "TASK" -> "OPEN_TASK";
            default -> throw new IllegalStateException();
        };
        List<String> actions = "DRAFT".equals(row.getSourceType())
                ? List.of(action, "DISCARD_DRAFT") : List.of(action);
        String evidence = switch (row.getSourceType()) {
            case "DRAFT" -> "HALL_REQUEST_DRAFT";
            case "TASK" -> "AGENT_TASK_META";
            default -> "PERSONAL_WORKSPACE_EXECUTION";
        };
        PersonalMark mark = Set.of("PRIVATE_CASE", "LEGACY_EXECUTION").contains(row.getSourceType())
                ? new PersonalMark(row.getMarkRevision() == null ? 0 : row.getMarkRevision(), Boolean.TRUE.equals(row.getArchived()),
                    row.getViewedExecutionId() == null ? null : new HallPrivateMarkService.ResultRef(row.getViewedExecutionId(), row.getViewedManifestId())) : null;
        Review review = "TASK".equals(row.getSourceType()) && Boolean.TRUE.equals(row.getReviewReady())
                ? new Review("FORMAL_DELIVERY_SUBMITTED", row.getDeliveryId(), row.getWorkItemId(),
                    Long.toString(row.getDeliveryVersion()), Long.toString(row.getTaskVersion())) : null;
        return new ItemSummary(new Ref(row.getSourceType(), row.getSourceId()), row.getTitle(),
                new Status(row.getState(), evidence, asOf), row.getTargetAgentId() == null ? null
                    : new TargetAgent(row.getTargetAgentId()), action, actions, row.getUpdatedAt(), mark, review);
    }

    private void validate(OwnerScope scope, String kind, String view, HallItemRow row) {
        if (row == null || !scope.tenantId().equals(row.getTenantId())
                || !scope.clientId().equals(row.getClientId()) || !scope.ownerJiacn().equals(row.getOwnerJiacn())
                || !id(row.getSourceId(), 100) || row.getUpdatedAt() == null || row.getUpdatedAt() < 0
                || row.getUpdatedAt() > 9_007_199_254_740_991L
                || row.getState() == null || !sourceType(kind, row.getSourceType())
                || (row.getTargetAgentId() != null && !id(row.getTargetAgentId(), 100))
                || (row.getTitle() != null && !title(row.getTitle()))) {
            throw new IllegalStateException();
        }
        boolean valid = switch (kind) {
            case "draft" -> "EDITING".equals(row.getState());
            case "private" -> PRIVATE_STATES.contains(row.getState());
            case "task" -> TASK_STATES.contains(row.getState());
            default -> false;
        };
        if (!valid) throw new IllegalStateException();
        if ("private".equals(kind)) {
            if (!id(row.getExecutionId(),100) || row.getMarkRevision()==null || row.getArchived()==null
                    || (row.getViewedExecutionId() == null) != (row.getViewedManifestId() == null)
                    || (row.getViewedExecutionId()!=null && (!id(row.getViewedExecutionId(),100) || !id(row.getViewedManifestId(),100)))
                    || row.getMarkRevision() < 0 || row.getMarkRevision() > 9_007_199_254_740_991L
                    || ("archive".equals(view) != Boolean.TRUE.equals(row.getArchived()))) throw new IllegalStateException();
            if ("needsAction".equals(view) && !("FAILED".equals(row.getState())
                    || ("OUTPUT_COMMITTED".equals(row.getState()) && row.getExecutionId() != null
                        && !row.getExecutionId().equals(row.getViewedExecutionId())))) throw new IllegalStateException();
        }
        if ("task".equals(kind)) {
            if ("archive".equals(view) != "archived".equals(row.getState())) throw new IllegalStateException();
            if (formalEnabled && "reviewing".equals(row.getState()) && !Boolean.TRUE.equals(row.getReviewReady())) throw new IllegalStateException();
            if (Boolean.TRUE.equals(row.getReviewReady()) && (!"reviewing".equals(row.getState())
                    || !id(row.getDeliveryId(), 100) || !id(row.getWorkItemId(), 100)
                    || row.getDeliveryVersion() == null || row.getDeliveryVersion() < 0
                    || row.getTaskVersion() == null || row.getTaskVersion() < 0)) throw new IllegalStateException();
            if ("needsAction".equals(view) && !Set.of("failed", "blocked", "reviewing").contains(row.getState())) throw new IllegalStateException();
        }
    }

    private static Map<String, SourceStatus> statuses(Section section) {
        Map<String, SourceStatus> result = new LinkedHashMap<>();
        section.partitions().forEach((source, page) -> result.put(source,
                new SourceStatus(page.status(), page.errorCode())));
        return Map.copyOf(result);
    }

    private static boolean sourceType(String kind, String type) {
        return type != null && switch (kind) {
            case "private" -> Set.of("PRIVATE_CASE", "LEGACY_EXECUTION").contains(type);
            case "task" -> "TASK".equals(type);
            case "draft" -> "DRAFT".equals(type);
            default -> false;
        };
    }
    private static int compare(Cursor left, Cursor right) {
        int result = Long.compare(left.updatedAt(), right.updatedAt());
        if (result == 0) result = binary(left.sourceType(), right.sourceType());
        return result == 0 ? binary(left.id(), right.id()) : result;
    }
    private static int binary(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(OwnerScope scope, String kind, String view, String q, Cursor cursor) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(1); out.writeUTF(binding(scope, kind, view, q));
                out.writeLong(cursor.updatedAt()); out.writeUTF(cursor.sourceType()); out.writeUTF(cursor.id());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Cursor decode(OwnerScope scope, String kind, String view, String q, String encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                Base64.getUrlDecoder().decode(encoded)))) {
            if (in.readInt() != 1 || !binding(scope, kind, view, q).equals(in.readUTF())) throw bad();
            Cursor cursor = new Cursor(in.readLong(), in.readUTF(), in.readUTF());
            if (in.available() != 0 || cursor.updatedAt() < 0 || cursor.updatedAt() > 9_007_199_254_740_991L
                    || !sourceType(kind, cursor.sourceType()) || !id(cursor.id(), 100)
                    || !encoded.equals(encode(scope, kind, view, q, cursor))) throw bad();
            return cursor;
        } catch (IOException | IllegalArgumentException malformed) { throw bad(); }
    }
    private static String binding(OwnerScope scope, String kind, String view, String q) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                for (String field : List.of(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), kind, view, q)) {
                    byte[] value = field.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(value.length); out.write(value);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireScope(OwnerScope scope) {
        if (scope == null || !"0".equals(scope.tenantId()) || !id(scope.clientId(), 50)
                || !id(scope.ownerJiacn(), 50) || "0".equals(scope.ownerJiacn())) throw bad();
    }
    private static boolean id(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip()) && text(value, max, false);
    }
    private static boolean title(String value) {
        return value.codePointCount(0, value.length()) <= 200
                && value.codePoints().noneMatch(c -> (Character.isISOControl(c)
                    && c != '\n' && c != '\r' && c != '\t')
                    || (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE));
    }
    private static boolean text(String value, int max, boolean allowEmpty) {
        return value != null && (allowEmpty || !value.isEmpty())
                && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(c -> Character.isISOControl(c)
                    || (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE));
    }
    private static Failure bad() { return new Failure(Reason.BAD_REQUEST); }
    private record Cursor(long updatedAt, String sourceType, String id) { }
}
