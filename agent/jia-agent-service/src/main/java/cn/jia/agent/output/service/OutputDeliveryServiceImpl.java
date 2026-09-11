package cn.jia.agent.output.service;

import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.OutputDeliveryService;
import cn.jia.agent.output.OutputObjectStorage;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.OutputVersionProvider;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dto.OutputCapabilitiesDTO;
import cn.jia.agent.output.dto.OutputDetailDTO;
import cn.jia.agent.output.dto.OutputDownloadDTO;
import cn.jia.agent.output.dto.OutputPageDTO;
import cn.jia.agent.output.dto.OutputPublishDTO;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.agent.output.dto.OutputSummaryDTO;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class OutputDeliveryServiceImpl implements OutputDeliveryService {
    private static final String ACTOR = "RUN_TICKET";
    private static final Set<String> TYPES = Set.of("summary", "document", "patch", "commit",
            "test_report", "analysis", "dataset", "link");
    private static final Set<String> VISIBILITIES = Set.of("task_members", "reviewer", "private");
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final OutputRunAuthorizationService authorization;
    private final OutputUploadDao dao;
    private final OutputObjectStorage storage;
    private final TransactionTemplate tx;
    private final Map<String, OutputVersionProvider> providers;
    private final boolean writesPaused;
    private final byte[] cursorKey;

    public OutputDeliveryServiceImpl(OutputRunAuthorizationService authorization,
            OutputUploadDao dao, OutputObjectStorage storage,
            PlatformTransactionManager transactionManager, OutputDeliveryProperties properties,
            List<OutputVersionProvider> providers) {
        this.authorization = Objects.requireNonNull(authorization);
        this.dao = Objects.requireNonNull(dao);
        this.storage = Objects.requireNonNull(storage);
        this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.writesPaused = properties.writesPaused();
        Map<String, OutputVersionProvider> mapped = new LinkedHashMap<>();
        for (OutputVersionProvider provider : providers) {
            if (mapped.putIfAbsent(provider.sourceType(), provider) != null)
                throw new IllegalStateException("Duplicate output version provider " + provider.sourceType());
        }
        this.providers = Map.copyOf(mapped);
        String configured = properties.cursorSigningKey();
        if (configured == null) configured = properties.storageSecretKey();
        if (configured == null) {
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random); this.cursorKey = random;
        } else this.cursorKey = sha256(configured.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public OutputCapabilitiesDTO capabilities() {
        return new OutputCapabilitiesDTO(!writesPaused, true, true, false,
                Long.toString(OutputConstants.DEFAULT_MAX_FILE_BYTES),
                Long.toString(OutputConstants.DEFAULT_MAX_RUN_BYTES),
                OutputConstants.DEFAULT_MAX_FILES, OutputConstants.SUPPORTED_MIME_TYPES);
    }

    @Override
    public OutputSummaryDTO publish(String bearer, String key, String sourceType,
            String sourceId, OutputPublishDTO request) {
        requireKey(key); validatePublish(sourceType, sourceId, request);
        byte[] requestHash = sha256(canonical(request, sourceType, sourceId));
        OutputSummaryDTO result = withLockRetry(() -> tx.execute(status -> {
            String rawBearer = rawBearer(bearer);
            OutputTicketAuthorization receiptAuth = authorization.authorizeTicket(
                    rawBearer, OutputConstants.OP_STATUS, true);
            if (!receiptAuth.runId().equals(request.runId())
                    || !receiptAuth.sourceType().equals(sourceType)
                    || !receiptAuth.sourceId().equals(sourceId)) throw forbidden();
            String operation = OutputConstants.SOURCE_TASK.equals(sourceType)
                    ? "publishTaskOutput" : "publishChatOutput";
            OutputUploadDao.Receipt receipt = dao.lockReceipt(
                    receiptAuth.tenantId(), receiptAuth.clientId(), ACTOR,
                    receiptAuth.runId(), operation, key);
            if (receipt != null) {
                if (!MessageDigest.isEqual(requestHash, receipt.requestHash()))
                    throw conflict("OUTPUT_IDEMPOTENCY_CONFLICT");
                return readReceipt(receipt.responseJson());
            }
            if (writesPaused) throw unavailable("OUTPUT_WRITES_PAUSED");
            OutputTicketAuthorization auth = authorization.authorizeTicket(
                    rawBearer, OutputConstants.OP_PUBLISH, false);
            if (!OutputConstants.RUN_ACTIVE.equals(auth.runState())
                    || !auth.operations().contains(OutputConstants.OP_PUBLISH)) throw forbidden();
            if (!auth.runId().equals(request.runId()) || !auth.sourceType().equals(sourceType)
                    || !auth.sourceId().equals(sourceId)
                    || !auth.tenantId().equals(receiptAuth.tenantId())
                    || !auth.clientId().equals(receiptAuth.clientId())
                    || !auth.producerAgentId().equals(receiptAuth.producerAgentId())
                    || !auth.bindingId().equals(receiptAuth.bindingId())) throw forbidden();
            OutputVersionProvider provider = provider(sourceType);
            provider.requireOwner(auth.tenantId(), auth.clientId(), auth.tenantId(), sourceId, true);
            long expected = decimal(request.expectedPreviousVersion(), true);
            long version = decimal(request.version(), false);
            if (version != expected + 1 || version > Integer.MAX_VALUE - 1L
                    && OutputConstants.SOURCE_TASK.equals(sourceType))
                throw bad("OUTPUT_VERSION_INVALID");
            OutputVersionProvider.PublishRow latest = provider.findLatestForUpdate(
                    auth.tenantId(), auth.clientId(), sourceId, request.outputId());
            long persisted = latest == null ? 0 : latest.version();
            if (persisted != expected) throw conflict("OUTPUT_VERSION_CONFLICT");
            long now = System.currentTimeMillis();
            Material material = material(auth, request);
            Long ownerSharedAt = OutputConstants.SOURCE_CONVERSATION.equals(sourceType)
                    || Boolean.TRUE.equals(request.publishToOwner()) ? now : null;
            String publicationKind = OutputConstants.SOURCE_CONVERSATION.equals(sourceType)
                    ? "CONVERSATION_OUTPUT" : ownerSharedAt == null ? "INTERNAL" : "OWNER_SHARE";
            String visibility = request.visibility() == null ? "task_members" : request.visibility();
            OutputVersionProvider.PublishRow row = new OutputVersionProvider.PublishRow(
                    auth.tenantId(), auth.clientId(), sourceId, request.outputId(), version,
                    auth.runId(), auth.producerAgentId(), request.title().strip(), material.fileName(),
                    request.artifactType(), request.content(), request.objectId(), material.hash(),
                    material.size(), material.mime(), publicationKind, visibility,
                    request.workItemId(), ownerSharedAt,
                    Math.addExact(now, OutputConstants.OUTPUT_RETENTION_MILLIS), now);
            try {
                if (provider.insert(row) != 1) throw unavailable("OUTPUT_PUBLICATION_FAILED");
                if (row.objectId() != null) insertPublicationReference(row, material.object());
                provider.appendPublicationEvent(row);
            } catch (DataIntegrityViolationException duplicate) {
                throw conflict("OUTPUT_VERSION_CONFLICT");
            }
            OutputSummaryDTO response = summary(row);
            String json = writeReceipt(response);
            if (dao.insertReceipt(auth.tenantId(), auth.clientId(), ACTOR, auth.runId(),
                    operation, key, requestHash, 200, json,
                    Math.addExact(now, OutputConstants.RECEIPT_RETENTION_MILLIS), now) != 1)
                throw unavailable("OUTPUT_RECEIPT_FAILED");
            return response;
        }));
        if (result == null) throw unavailable("OUTPUT_TRANSACTION_EMPTY");
        return result;
    }

    @Override
    public OutputPageDTO list(String tenantId, String clientId, String jiacn, String sourceType,
            String sourceId, String cursor, Integer requestedLimit) {
        return page(tenantId, clientId, jiacn, sourceType, sourceId, null, true,
                cursor, requestedLimit);
    }

    @Override
    public OutputPageDTO listVersions(String tenantId, String clientId, String jiacn,
            String sourceType, String sourceId, String outputId, String cursor,
            Integer requestedLimit) {
        requireId(outputId, 100);
        return page(tenantId, clientId, jiacn, sourceType, sourceId, outputId, false,
                cursor, requestedLimit);
    }

    private OutputPageDTO page(String tenantId, String clientId, String jiacn, String sourceType,
            String sourceId, String outputId, boolean latestOnly, String cursor,
            Integer requestedLimit) {
        requireScope(tenantId, clientId, jiacn); requireSource(sourceType, sourceId);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 100) throw bad("OUTPUT_LIMIT_INVALID");
        Cursor decoded = cursor == null ? null : decodeCursor(cursor, tenantId, clientId,
                sourceType, sourceId, outputId == null ? "*" : outputId, latestOnly);
        long snapshotAt = decoded == null ? System.currentTimeMillis() : decoded.snapshotAt();
        OutputVersionProvider.CursorBoundary after = decoded == null ? null
                : new OutputVersionProvider.CursorBoundary(decoded.createdAt(),
                        decoded.outputId(), decoded.version());
        List<OutputVersionProvider.PublishRow> rows = tx.execute(status -> {
            OutputVersionProvider p = provider(sourceType);
            p.requireOwner(tenantId, clientId, jiacn, sourceId, false);
            return p.list(tenantId, clientId, sourceId, outputId, latestOnly,
                    snapshotAt, after, limit + 1);
        });
        if (rows == null) throw unavailable("OUTPUT_TRANSACTION_EMPTY");
        boolean more = rows.size() > limit;
        List<OutputVersionProvider.PublishRow> selected = rows.subList(0, Math.min(limit, rows.size()));
        List<OutputSummaryDTO> items = selected.stream().map(this::summary).toList();
        String next = null;
        if (more && !selected.isEmpty()) {
            OutputVersionProvider.PublishRow last = selected.getLast();
            next = encodeCursor(tenantId, clientId, sourceType, sourceId,
                    outputId == null ? "*" : outputId, latestOnly, snapshotAt,
                    last.createdAt(), last.outputId(), last.version());
        }
        return new OutputPageDTO(items, next, Long.toString(snapshotAt));
    }

    @Override
    public OutputDetailDTO getVersion(String tenantId, String clientId, String jiacn,
            String sourceType, String sourceId, String outputId, String version) {
        OutputVersionProvider.PublishRow row = authorizedVersion(tenantId, clientId, jiacn,
                sourceType, sourceId, outputId, version, false);
        if (row.objectId() != null) requireReadyObject(tenantId, clientId, row, false);
        return new OutputDetailDTO(summary(row), row.content());
    }

    @Override
    public OutputDownloadDTO downloadVersion(String tenantId, String clientId, String jiacn,
            String sourceType, String sourceId, String outputId, String version) {
        long numeric = decimal(version, false);
        DownloadPlan plan = tx.execute(status -> {
            requireScope(tenantId, clientId, jiacn); requireSource(sourceType, sourceId);
            requireId(outputId, 100);
            OutputVersionProvider p = provider(sourceType);
            p.requireOwner(tenantId, clientId, jiacn, sourceId, false);
            OutputVersionProvider.PublishRow row = requireReadable(
                    p.findVersion(tenantId, clientId, sourceId, outputId, numeric), sourceType);
            if (row.retainUntil() <= System.currentTimeMillis()) throw gone();
            if (row.objectId() == null) return new DownloadPlan(row, null, null);
            OutputUploadDao.ObjectRow object = requireReadyObject(tenantId, clientId, row, true);
            byte[] pin = referenceKey(tenantId, clientId, sourceType, sourceId, outputId,
                    numeric, "READ_PIN", UUID.randomUUID().toString());
            long now = System.currentTimeMillis();
            OutputUploadDao.ReferenceRow reference = new OutputUploadDao.ReferenceRow(
                    tenantId, clientId, pin, row.objectId(), sourceType, sourceId, outputId,
                    numeric, "READ_PIN", null, "ACTIVE",
                    Math.addExact(now, OutputConstants.READ_PIN_MILLIS), false, null, null);
            if (dao.insertReference(reference, now) != 1) throw unavailable("OUTPUT_READ_PIN_FAILED");
            return new DownloadPlan(row, object, pin);
        });
        if (plan == null) throw unavailable("OUTPUT_TRANSACTION_EMPTY");
        if (plan.object() == null) {
            byte[] bytes = plan.row().content().getBytes(StandardCharsets.UTF_8);
            return download(plan.row(), new ByteArrayInputStream(bytes));
        }
        try {
            InputStream raw = storage.open(plan.object().bucket(), plan.object().storageKey(),
                    plan.object().storageVersion());
            return download(plan.row(), new PinnedInputStream(raw, tenantId, clientId,
                    plan.row().objectId(), plan.pin()));
        } catch (IOException openFailure) {
            releasePin(tenantId, clientId, plan.row().objectId(), plan.pin());
            throw unavailable("OUTPUT_STORAGE_UNAVAILABLE");
        }
    }

    private OutputVersionProvider.PublishRow authorizedVersion(String tenantId, String clientId,
            String jiacn, String sourceType, String sourceId, String outputId, String version,
            boolean lockSource) {
        long numeric = decimal(version, false); requireScope(tenantId, clientId, jiacn);
        requireSource(sourceType, sourceId); requireId(outputId, 100);
        OutputVersionProvider.PublishRow row = tx.execute(status -> {
            OutputVersionProvider p = provider(sourceType);
            p.requireOwner(tenantId, clientId, jiacn, sourceId, lockSource);
            return requireReadable(p.findVersion(tenantId, clientId, sourceId, outputId, numeric),
                    sourceType);
        });
        if (row == null) throw hidden();
        if (row.retainUntil() <= System.currentTimeMillis()) throw gone();
        return row;
    }

    private OutputVersionProvider.PublishRow requireReadable(OutputVersionProvider.PublishRow row,
            String sourceType) {
        if (row == null) throw hidden();
        if (OutputConstants.SOURCE_TASK.equals(sourceType) && row.ownerSharedAt() == null) throw hidden();
        return row;
    }

    private Material material(OutputTicketAuthorization auth, OutputPublishDTO request) {
        if (request.objectId() == null) {
            byte[] bytes = request.content().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 262_144) throw tooLarge("OUTPUT_INLINE_TOO_LARGE");
            return new Material(sha256(bytes), bytes.length, "text/plain", null, null);
        }
        OutputUploadDao.ObjectRow object = dao.findObject(
                auth.tenantId(), auth.clientId(), request.objectId(), true);
        if (object == null || !auth.runId().equals(object.runId())
                || !"PASSED".equals(object.verificationStatus())
                || !"READY".equals(object.lifecycleStatus()) || object.storageKey() == null
                || object.actualSha256() == null || object.actualSha256().length != 32
                || object.actualSize() == null || object.actualSize() < 0 || object.actualMime() == null)
            throw conflict("OUTPUT_OBJECT_NOT_READY");
        OutputUploadDao.UploadRow upload = dao.findUploadByObject(
                auth.tenantId(), auth.clientId(), request.objectId(), false);
        if (upload == null || !auth.runId().equals(upload.runId())
                || !"READY".equals(upload.state())) throw conflict("OUTPUT_OBJECT_NOT_READY");
        return new Material(object.actualSha256(), object.actualSize(), object.actualMime(),
                upload.fileName(), object);
    }

    private OutputUploadDao.ObjectRow requireReadyObject(String tenantId, String clientId,
            OutputVersionProvider.PublishRow row, boolean lock) {
        OutputUploadDao.ObjectRow object = dao.findObject(tenantId, clientId, row.objectId(), lock);
        if (object == null || !Objects.equals(row.runId(), object.runId())
                || !"PASSED".equals(object.verificationStatus())
                || !"READY".equals(object.lifecycleStatus()) || object.storageKey() == null
                || object.actualSize() == null
                || !Objects.equals(row.contentByteLength(), object.actualSize())
                || !Arrays.equals(row.contentHash(), object.actualSha256())
                || !Objects.equals(row.mimeType(), object.actualMime()))
            throw unavailable("OUTPUT_OBJECT_UNAVAILABLE");
        return object;
    }

    private void insertPublicationReference(OutputVersionProvider.PublishRow row,
            OutputUploadDao.ObjectRow object) {
        if (object == null || !"READY".equals(object.lifecycleStatus()))
            throw conflict("OUTPUT_OBJECT_NOT_READY");
        String kind = row.ownerSharedAt() == null ? "ROLE_CANDIDATE" : "OWNER_SHARE";
        byte[] key = referenceKey(row.tenantId(), row.clientId(), sourceType(row), row.sourceId(),
                row.outputId(), row.version(), kind, "");
        OutputUploadDao.ReferenceRow ref = new OutputUploadDao.ReferenceRow(row.tenantId(),
                row.clientId(), key, row.objectId(), sourceType(row), row.sourceId(), row.outputId(),
                row.version(), kind, null, "ACTIVE", row.retainUntil(), false, null, null);
        if (dao.insertReference(ref, row.createdAt()) != 1)
            throw unavailable("OUTPUT_REFERENCE_FAILED");
    }

    private String sourceType(OutputVersionProvider.PublishRow row) {
        return "CONVERSATION_OUTPUT".equals(row.publicationKind())
                ? OutputConstants.SOURCE_CONVERSATION : OutputConstants.SOURCE_TASK;
    }

    private OutputSummaryDTO summary(OutputVersionProvider.PublishRow row) {
        String state = row.retainUntil() <= System.currentTimeMillis() ? "EXPIRED" : "AVAILABLE";
        String preview = row.content() != null ? "TEXT"
                : row.mimeType() != null && row.mimeType().startsWith("image/") ? "IMAGE" : "NONE";
        return new OutputSummaryDTO(new OutputSourceDTO(sourceType(row), row.sourceId()),
                row.outputId(), Long.toString(row.version()), row.title(), row.fileName(),
                row.mimeType(), Long.toString(row.contentByteLength()),
                HexFormat.of().formatHex(row.contentHash()), Long.toString(row.createdAt()), state,
                row.publicationKind(), preview, "AVAILABLE".equals(state), null);
    }

    private OutputDownloadDTO download(OutputVersionProvider.PublishRow row, InputStream stream) {
        String name = row.fileName() == null ? row.outputId() + ".txt" : row.fileName();
        return new OutputDownloadDTO(name, row.mimeType(), row.contentByteLength(),
                HexFormat.of().formatHex(row.contentHash()), stream);
    }

    private String encodeCursor(String tenantId, String clientId, String sourceType,
            String sourceId, String filter, boolean latestOnly, long snapshotAt,
            long createdAt, String outputId, long version) {
        long expires = Math.addExact(System.currentTimeMillis(), OutputConstants.CURSOR_TTL_MILLIS);
        String payload = String.join("|", "1", b64(tenantId), b64(clientId), sourceType,
                b64(sourceId), b64(filter), latestOnly ? "1" : "0", Long.toString(snapshotAt),
                Long.toString(createdAt), b64(outputId), Long.toString(version), Long.toString(expires));
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(bytes));
    }

    private Cursor decodeCursor(String token, String tenantId, String clientId, String sourceType,
            String sourceId, String filter, boolean latestOnly) {
        try {
            String[] pair = token.split("\\.", -1);
            if (pair.length != 2 || token.length() > 2048) throw new IllegalArgumentException();
            byte[] payload = Base64.getUrlDecoder().decode(pair[0]);
            byte[] signature = Base64.getUrlDecoder().decode(pair[1]);
            if (!MessageDigest.isEqual(signature, hmac(payload))) throw new IllegalArgumentException();
            String[] p = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (p.length != 12 || !"1".equals(p[0]) || !tenantId.equals(unb64(p[1]))
                    || !clientId.equals(unb64(p[2])) || !sourceType.equals(p[3])
                    || !sourceId.equals(unb64(p[4])) || !filter.equals(unb64(p[5]))
                    || (latestOnly ? !"1".equals(p[6]) : !"0".equals(p[6])))
                throw new IllegalArgumentException();
            Cursor result = new Cursor(Long.parseLong(p[7]), Long.parseLong(p[8]),
                    unb64(p[9]), Long.parseLong(p[10]), Long.parseLong(p[11]));
            if (result.expiresAt() < System.currentTimeMillis()) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) {
            throw bad("OUTPUT_CURSOR_INVALID");
        }
    }

    private byte[] hmac(byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private void renewPin(String tenantId, String clientId, String objectId, byte[] pin,
            long retainUntil) {
        tx.executeWithoutResult(status -> {
            OutputUploadDao.ObjectRow object = dao.findObject(tenantId, clientId, objectId, true);
            if (object == null || !"READY".equals(object.lifecycleStatus()))
                throw unavailable("OUTPUT_OBJECT_UNAVAILABLE");
            OutputUploadDao.ReferenceRow ref = dao.findReference(tenantId, clientId, pin, true);
            if (ref == null || !objectId.equals(ref.objectId()) || !"READ_PIN".equals(ref.referenceKind())
                    || !"ACTIVE".equals(ref.state())
                    || dao.renewActiveReference(tenantId, clientId, pin, retainUntil,
                    System.currentTimeMillis()) != 1) throw unavailable("OUTPUT_READ_PIN_LOST");
        });
    }

    private void releasePin(String tenantId, String clientId, String objectId, byte[] pin) {
        if (pin == null) return;
        try {
            tx.executeWithoutResult(status -> {
                dao.findObject(tenantId, clientId, objectId, true);
                OutputUploadDao.ReferenceRow ref = dao.findReference(tenantId, clientId, pin, true);
                if (ref != null && objectId.equals(ref.objectId()) && "ACTIVE".equals(ref.state()))
                    dao.releaseReference(tenantId, clientId, pin, System.currentTimeMillis());
            });
        } catch (RuntimeException ignored) { }
    }

    private final class PinnedInputStream extends FilterInputStream {
        private final String tenantId, clientId, objectId;
        private final byte[] pin;
        private final long deadline = Math.addExact(System.currentTimeMillis(),
                OutputConstants.DOWNLOAD_DEADLINE_MILLIS);
        private long nextRenew = Math.addExact(System.currentTimeMillis(),
                OutputConstants.READ_PIN_RENEW_MILLIS);
        private boolean closed;
        private PinnedInputStream(InputStream in, String tenantId, String clientId,
                String objectId, byte[] pin) {
            super(in); this.tenantId=tenantId; this.clientId=clientId;
            this.objectId=objectId; this.pin=pin;
        }
        @Override public int read() throws IOException { int value = super.read(); tick(); return value; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int count = super.read(b, off, len); tick(); return count;
        }
        private void tick() throws IOException {
            long now = System.currentTimeMillis();
            if (now > deadline) { close(); throw new IOException("Output stream deadline exceeded"); }
            if (now >= nextRenew) {
                try { renewPin(tenantId, clientId, objectId, pin,
                        Math.addExact(deadline, 60_000L)); }
                catch (RuntimeException lost) { close(); throw new IOException("Output read pin lost", lost); }
                nextRenew = Math.addExact(now, OutputConstants.READ_PIN_RENEW_MILLIS);
            }
        }
        @Override public void close() throws IOException {
            if (closed) return; closed = true;
            try { super.close(); } finally { releasePin(tenantId, clientId, objectId, pin); }
        }
    }

    private OutputVersionProvider provider(String type) {
        OutputVersionProvider provider = providers.get(type);
        if (provider == null) throw unavailable("OUTPUT_SOURCE_UNAVAILABLE");
        return provider;
    }
    private static void validatePublish(String type, String sourceId, OutputPublishDTO r) {
        requireSource(type, sourceId);
        if (r == null) throw bad("OUTPUT_REQUEST_INVALID");
        requireId(r.runId(), 100); requireId(r.outputId(), 100);
        requireId(r.artifactType(), 30); if (!TYPES.contains(r.artifactType())) throw bad("OUTPUT_TYPE_INVALID");
        requireText(r.title(), 255); decimal(r.expectedPreviousVersion(), true); decimal(r.version(), false);
        if ((r.content() == null) == (r.objectId() == null)) throw bad("OUTPUT_PAYLOAD_INVALID");
        if (r.objectId() != null) requireId(r.objectId(), 100);
        if (r.workItemId() != null) requireId(r.workItemId(), 100);
        String visibility = r.visibility() == null ? "task_members" : r.visibility();
        if (!VISIBILITIES.contains(visibility)) throw bad("OUTPUT_VISIBILITY_INVALID");
        if (OutputConstants.SOURCE_CONVERSATION.equals(type)
                && (r.workItemId() != null || r.visibility() != null || r.publishToOwner() != null))
            throw bad("OUTPUT_REQUEST_INVALID");
    }
    private static void requireScope(String tenant, String client, String jiacn) {
        requireId(tenant, 50); requireId(client, 50); requireId(jiacn, 50);
        if (!tenant.equals(jiacn)) throw hidden();
    }
    private static void requireSource(String type, String id) {
        if (!Set.of(OutputConstants.SOURCE_TASK, OutputConstants.SOURCE_CONVERSATION).contains(type))
            throw bad("OUTPUT_SOURCE_INVALID");
        requireId(id, 400);
        if (OutputConstants.SOURCE_CONVERSATION.equals(type)) {
            try { long value=Long.parseLong(id); if(value<=0||!Long.toString(value).equals(id))throw new NumberFormatException(); }
            catch(NumberFormatException invalid){throw bad("OUTPUT_SOURCE_INVALID");}
        }
    }
    private static void requireId(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) throw bad("OUTPUT_ID_INVALID");
    }
    private static void requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) throw bad("OUTPUT_TEXT_INVALID");
    }
    private static long decimal(String value, boolean zeroAllowed) {
        try {
            if (value == null || !(zeroAllowed ? value.matches("0|[1-9][0-9]{0,18}")
                    : value.matches("[1-9][0-9]{0,18}"))) throw new NumberFormatException();
            long parsed = Long.parseLong(value); if (parsed == Long.MAX_VALUE) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) { throw bad("OUTPUT_VERSION_INVALID"); }
    }
    private static void requireKey(String value) {
        if (value == null || !value.matches("[\\x21-\\x7e]{16,100}")) throw bad("IDEMPOTENCY_KEY_INVALID");
    }
    private static String rawBearer(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.length() < 48)
            throw new OutputDeliveryException("OUTPUT_AUTH_UNAUTHORIZED", "Output ticket is required", 401, false);
        return bearer.substring(7);
    }
    private static byte[] canonical(OutputPublishDTO r, String type, String sourceId) {
        String value = String.join("\0", type, sourceId, value(r.runId()),
                value(r.expectedPreviousVersion()), value(r.title()), value(r.artifactType()),
                value(r.content()), value(r.objectId()), value(r.outputId()), value(r.version()),
                value(r.workItemId()), value(r.visibility()), value(r.publishToOwner()));
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static String value(Object value) { return value == null ? "<null>" : value.toString(); }
    private static byte[] referenceKey(String tenant, String client, String type, String source,
            String output, long version, String kind, String nonce) {
        return sha256(String.join("\0", tenant, client, type, source, output,
                Long.toString(version), kind, nonce).getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String b64(String value) { return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String unb64(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static String writeReceipt(OutputSummaryDTO value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception impossible) { throw unavailable("OUTPUT_RECEIPT_FAILED"); }
    }
    private static OutputSummaryDTO readReceipt(String value) {
        try { return JSON.readValue(value, OutputSummaryDTO.class); }
        catch (Exception corrupt) { throw unavailable("OUTPUT_RECEIPT_INVALID"); }
    }
    private static OutputDeliveryException bad(String code) { return new OutputDeliveryException(code, "Invalid output request", 400, false); }
    private static OutputDeliveryException forbidden() { return new OutputDeliveryException("OUTPUT_AUTH_FORBIDDEN", "Output access is unavailable", 403, false); }
    private static OutputDeliveryException hidden() { return new OutputDeliveryException("OUTPUT_NOT_FOUND", "Output is unavailable", 404, false); }
    private static OutputDeliveryException gone() { return new OutputDeliveryException("OUTPUT_EXPIRED", "Output has expired", 410, false); }
    private static OutputDeliveryException conflict(String code) { return new OutputDeliveryException(code, "Output state conflict", 409, false); }
    private static OutputDeliveryException tooLarge(String code) { return new OutputDeliveryException(code, "Output is too large", 413, false); }
    private static OutputDeliveryException unavailable(String code) { return new OutputDeliveryException(code, "Output delivery unavailable", 503, true); }
    private static <T> T withLockRetry(Supplier<T> action) {
        PessimisticLockingFailureException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException contention) {
                last = contention;
                try {
                    Thread.sleep(5L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw conflict("OUTPUT_VERSION_CONFLICT");
                }
            }
        }
        if (last != null) throw conflict("OUTPUT_VERSION_CONFLICT");
        throw unavailable("OUTPUT_TRANSACTION_EMPTY");
    }
    private record Material(byte[] hash, long size, String mime, String fileName,
                            OutputUploadDao.ObjectRow object) { }
    private record DownloadPlan(OutputVersionProvider.PublishRow row,
                                OutputUploadDao.ObjectRow object, byte[] pin) { }
    private record Cursor(long snapshotAt, long createdAt, String outputId,
                          long version, long expiresAt) { }
}
