package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchivePersonalDataStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore.OutboxRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.QuestionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveQuestionServiceTest {
    private static final String EDITION = ArchiveManifestLoader.EDITION_ID;
    private static final String BLOCK = EDITION + "-c001";
    private static final String P1 = BLOCK + "-p0001";
    private static final String P2 = BLOCK + "-p0002";
    private static final String P3 = BLOCK + "-p0003";
    private static final String MANIFEST = "a".repeat(64);
    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final ArchiveOwnerScope OWNER = new ArchiveOwnerScope("owner-a", "client-a", "owner-a");
    private static final ArchiveOwnerScope FOREIGN_CLIENT = new ArchiveOwnerScope("owner-a", "client-b", "owner-a");
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    private ArchiveQuestionTestSupport.Store store;
    private ArchiveQuestionTestSupport.Content content;
    private ArchiveQuestionTestSupport.Transactions transactions;
    private ArchiveQuestionEventBroker broker;
    private ArchiveQuestionEventDelivery delivery;
    private ArchiveQuestionServiceImpl service;
    private String anchor;

    @BeforeEach
    void setUp() {
        store = new ArchiveQuestionTestSupport.Store();
        content = new ArchiveQuestionTestSupport.Content();
        transactions = new ArchiveQuestionTestSupport.Transactions(store);
        broker = new ArchiveQuestionEventBroker();
        delivery = new ArchiveQuestionEventDelivery(store, broker, transactions);
        content.active = new ArchivePersonalDataStore.ActiveEdition(EDITION, MANIFEST);
        String first = "甲水泊";
        String second = "忠义😊";
        content.points.put(P1, point(P1, 1, first));
        content.points.put(P2, point(P2, 2, second));
        content.points.put(P3, point(P3, 3, "第三段"));
        String selected = "水泊\n\n忠义";
        anchor = "{\"editionManifestSha256\":\"" + MANIFEST + "\",\"blockType\":\"CHAPTER\","
                + "\"blockId\":\"" + BLOCK + "\",\"segments\":[{\"paragraphId\":\"" + P1
                + "\",\"startByte\":3,\"endByte\":9,\"paragraphSha256\":\"" + hash(first) + "\"},{"
                + "\"paragraphId\":\"" + P2 + "\",\"startByte\":0,\"endByte\":6,"
                + "\"paragraphSha256\":\"" + hash(second) + "\"}],\"selectionSha256\":\""
                + hash(selected) + "\"}";
        service = service(new ArchiveClerkFallbackProvider());
    }

    @Test
    void createPersistsAuthoritativeMultilingualSelectionFixedResponderAndOneDispatch() {
        ArchiveMutationResult result = service.create(OWNER, ID, path(), "create-key", body("何谓忠义？"));
        assertEquals(202, result.status());
        assertFalse(result.replayed());
        var snapshot = service.get(OWNER, ID);
        assertEquals("1", snapshot.version());
        assertEquals("1", snapshot.currentSequence());
        assertEquals("QUEUED", snapshot.status());
        assertEquals("archive-clerk-v1", snapshot.responder().id());
        assertEquals("案卷书吏", snapshot.responder().displayName());
        assertEquals("fallback", snapshot.responder().mode());
        assertEquals("水泊\n\n忠义", snapshot.selectedText());
        assertEquals("", snapshot.answer());
        assertEquals(1, store.questionInserts);
        assertEquals(1, store.eventInserts);
        assertEquals(1, store.outboxInserts);
        assertEquals(1, store.findOutbox(OWNER, ID, false).publishedSequence());
    }

    @Test
    void canonicalEquivalentResponseLossReplayIsByteExactAndNeverDuplicatesQuestionEventOrOutbox() {
        transactions.failAfterCommitOnce = true;
        assertThrows(IllegalStateException.class,
                () -> service.create(OWNER, ID, path(), "loss-key", body("何谓忠义？")));
        ArchiveMutationResult replay = service.create(OWNER, ID, path(), "loss-key", bytes(
                "{\"anchor\":" + anchor + ",\"question\":\"何谓忠义？\"}"));
        assertTrue(replay.replayed());
        ArchiveMutationResult secondReplay = service.create(OWNER, ID, path(), "loss-key", body("何谓忠义？"));
        assertArrayEquals(replay.body(), secondReplay.body());
        assertEquals(1, store.questionInserts);
        assertEquals(1, store.eventInserts);
        assertEquals(1, store.outboxInserts);

        ArchivePersonalDataException mismatch = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, ID, path(), "loss-key", body("不同问题")));
        assertEquals(409, mismatch.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", mismatch.code());
    }

    @Test
    void strictJsonAndRoutingLikeMembersFailBeforeMutationReservation() {
        List<String> invalid = List.of(
                "{\"question\":\"x\",\"question\":\"x\",\"anchor\":" + anchor + "}",
                "{\"question\":\"x\",\"anchor\":" + anchor + ",\"unknown\":1}",
                "{\"question\":\"x\",\"anchor\":" + anchor + ",\"name\":\"ordinary-unknown\"}",
                "{\"question\":\"x\",\"anchor\":" + anchor + "} {}",
                "{\"question\":1,\"anchor\":" + anchor + "}",
                "{\"question\":\"x\",\"anchor\":" + anchor.replace("\"startByte\":3", "\"startByte\":3.0") + "}",
                "{\"question\":null,\"anchor\":" + anchor + "}",
                "{}",
                "{\"question\":\"x\"}",
                "{\"anchor\":" + anchor + "}",
                "{\"question\":\"x\",\"anchor\":\"not-an-anchor\"}",
                "{\"question\":\"\\uD800\",\"anchor\":" + anchor + "}");
        for (int index = 0; index < invalid.size(); index++) {
            byte[] candidate = bytes(invalid.get(index));
            ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                    () -> service.create(OWNER, ID, path(), "strict-" + java.util.Arrays.hashCode(candidate), candidate));
            assertEquals(422, failure.status());
            assertEquals("INVALID_REQUEST_JSON", failure.code());
        }
        ArchivePersonalDataException bom = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, ID, path(), "bom", new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'}));
        assertEquals("INVALID_REQUEST_JSON", bom.code());
        for (String routing : List.of("targetAgentId", "agentId", "role", "roleName", "personaName",
                "responderId", "selectedAgent", "targetName", "TARGET_NAME", "target-name",
                "characterName", "CHARACTER_NAME", "character-name", "角色名", "吴用")) {
            ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                    () -> service.create(OWNER, ID, path(), "route-" + routing.hashCode(), bytes(
                            "{\"question\":\"x\",\"anchor\":" + anchor + ",\"" + routing + "\":\"吴用\"}")));
            assertEquals(422, failure.status());
            assertEquals("ROUTING_NOT_SUPPORTED", failure.code());
        }
        for (String nested : List.of(
                "{\"meta\":{\"target_name\":\"吴用\"}}",
                "{\"options\":[{\"Character-Name\":\"吴用\"}]}",
                "{\"routing\":{\"name\":\"吴用\"}}")) {
            ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                    () -> service.create(OWNER, ID, path(), "nested-route-" + nested.hashCode(), bytes(
                            "{\"question\":\"x\",\"anchor\":" + anchor + ",\"extension\":" + nested + "}")));
            assertEquals("ROUTING_NOT_SUPPORTED", failure.code());
        }
        assertEquals(0, store.mutationReservations);
    }

    @Test
    void anchorHashBoundaryContiguityAndQuestionUtf8LimitFailClosed() {
        for (String hashMismatch : List.of(
                anchor.replace(hash("水泊\n\n忠义"), "b".repeat(64)),
                anchor.replace(hash("甲水泊"), "c".repeat(64)))) {
            ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                    () -> service.create(OWNER, ID, path(), "hash-" + hashMismatch.hashCode(), bytes(
                            "{\"question\":\"x\",\"anchor\":" + hashMismatch + "}")));
            assertEquals("CONTENT_HASH_MISMATCH", failure.code());
        }
        for (String invalidAnchor : List.of(
                anchor.replace("\"startByte\":3", "\"startByte\":4"),
                anchor.replace("\"paragraphId\":\"" + P2 + "\"", "\"paragraphId\":\"missing\""),
                anchor.replace("\"blockType\":\"CHAPTER\"", "\"blockType\":\"PREFACE\""))) {
            ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                    () -> service.create(OWNER, ID, path(), "shape-" + invalidAnchor.hashCode(), bytes(
                            "{\"question\":\"x\",\"anchor\":" + invalidAnchor + "}")));
            assertEquals("INVALID_TEXT_ANCHOR", failure.code());
        }
        ArchivePersonalDataStore.ContentPoint original = content.points.get(P1);
        content.points.put(P1, new ArchivePersonalDataStore.ContentPoint(original.editionId(),
                original.manifestSha256(), original.blockType(), original.blockId(), original.blockOrdinal(),
                original.paragraphId(), original.paragraphOrdinal(), original.text(),
                original.utf8ByteLength() + 1, original.paragraphSha256()));
        ArchivePersonalDataException authoritative = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, ID, path(), "authoritative-length", body("x")));
        assertEquals("CONTENT_HASH_MISMATCH", authoritative.code());
        content.points.put(P1, new ArchivePersonalDataStore.ContentPoint(original.editionId(),
                original.manifestSha256(), original.blockType(), original.blockId(), original.blockOrdinal(),
                original.paragraphId(), original.paragraphOrdinal(), "乙水泊",
                original.utf8ByteLength(), original.paragraphSha256()));
        ArchivePersonalDataException authoritativeHash = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, ID, path(), "authoritative-hash", body("x")));
        assertEquals("CONTENT_HASH_MISMATCH", authoritativeHash.code());
        content.points.put(P1, original);

        ArchivePersonalDataException large = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, ID, path(), "large", body("水".repeat(2731))));
        assertEquals("INVALID_REQUEST_JSON", large.code());
        assertEquals(0, store.questionInserts);
    }



    @Test
    void prefaceSelectionAndAdversarialSegmentShapesUseAuthoritativeBytesOnly() {
        String prefaceBlock = EDITION + "-preface";
        String prefaceParagraph = prefaceBlock + "-p0001";
        String prefaceText = "引首忠义😊";
        content.points.put(prefaceParagraph, new ArchivePersonalDataStore.ContentPoint(
                EDITION, MANIFEST, "PREFACE", prefaceBlock, 0, prefaceParagraph, 1,
                prefaceText, bytes(prefaceText).length, hash(prefaceText)));
        String selected = "忠义😊";
        String prefaceAnchor = "{\"editionManifestSha256\":\"" + MANIFEST
                + "\",\"blockType\":\"PREFACE\",\"blockId\":\"" + prefaceBlock
                + "\",\"segments\":[{\"paragraphId\":\"" + prefaceParagraph
                + "\",\"startByte\":6,\"endByte\":16,\"paragraphSha256\":\""
                + hash(prefaceText) + "\"}],\"selectionSha256\":\"" + hash(selected) + "\"}";
        String prefaceId = "423e4567-e89b-42d3-a456-426614174000";
        service.create(OWNER, prefaceId, "/archive/v1/me/questions/" + prefaceId, "preface",
                bytes("{\"question\":\"何谓引首？\",\"anchor\":" + prefaceAnchor + "}"));
        assertEquals(selected, service.get(OWNER, prefaceId).selectedText());
        assertEquals("PREFACE", service.get(OWNER, prefaceId).anchor().blockType());

        String p3Text = "第三段";
        String nonContiguous = anchor.replace("\"paragraphId\":\"" + P2 + "\"",
                "\"paragraphId\":\"" + P3 + "\"")
                .replace(hash("忠义😊"), hash(p3Text))
                .replace("\"endByte\":6", "\"endByte\":9");
        ArchivePersonalDataException gap = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, ID, path(), "non-contiguous",
                        bytes("{\"question\":\"x\",\"anchor\":" + nonContiguous + "}")));
        assertEquals("INVALID_TEXT_ANCHOR", gap.code());

        String segment = "{\"paragraphId\":\"" + P1
                + "\",\"startByte\":0,\"endByte\":3,\"paragraphSha256\":\""
                + hash("甲水泊") + "\"}";
        String tooMany = "{\"editionManifestSha256\":\"" + MANIFEST
                + "\",\"blockType\":\"CHAPTER\",\"blockId\":\"" + BLOCK
                + "\",\"segments\":[" + java.util.stream.IntStream.range(0, 17)
                .mapToObj(ignored -> segment).collect(java.util.stream.Collectors.joining(","))
                + "],\"selectionSha256\":\"" + "0".repeat(64) + "\"}";
        ArchivePersonalDataException count = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, ID, path(), "too-many-segments",
                        bytes("{\"question\":\"x\",\"anchor\":" + tooMany + "}")));
        assertEquals("INVALID_REQUEST_JSON", count.code());
    }

    @Test
    void committedPendingMutationFailsClosedWithoutQuestionEventOrOutboxWrites() {
        byte[] requestBody = body("x");
        ArchiveWriteJson writeJson = new ArchiveWriteJson();
        ArchiveWriteJson.Parsed parsed = writeJson.parseQuestion(requestBody,
                cn.jia.chat.archive.dto.ArchiveQuestionPutRequest.class);
        String hash = writeJson.sha256("PUT", path(), parsed.canonicalJson());
        store.insertOrLockMutation(OWNER, ID, "PUT", path(), "pending-key", hash, NOW.plusSeconds(604800));

        ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, ID, path(), "pending-key", requestBody));
        assertEquals(409, failure.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", failure.code());
        assertEquals(0, store.questionInserts);
        assertEquals(0, store.eventInserts);
        assertEquals(0, store.outboxInserts);
    }


    @Test
    void retryJsonIsStrictAndRoutingFieldsUseDedicated422WithoutReservations() {
        for (String invalid : List.of(
                "{}", "{\"expectedVersion\":null}", "{\"expectedVersion\":1}",
                "{\"expectedVersion\":1.0}", "{\"expectedVersion\":true}",
                "{\"expectedVersion\":\"1\",\"unknown\":0}",
                "{\"expectedVersion\":\"1\"} {}")) {
            ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                    () -> service.retry(OWNER, ID, path() + "/retry", "strict-retry-" + invalid.hashCode(),
                            bytes(invalid)));
            assertEquals(422, failure.status());
            assertEquals("INVALID_REQUEST_JSON", failure.code());
        }
        for (String routed : List.of(
                "{\"expectedVersion\":\"1\",\"targetAgentId\":\"wuyong\"}",
                "{\"expectedVersion\":\"1\",\"TARGET_NAME\":\"wuyong\"}",
                "{\"expectedVersion\":\"1\",\"meta\":{\"character-name\":\"wuyong\"}}")) {
            ArchivePersonalDataException routing = assertThrows(ArchivePersonalDataException.class,
                    () -> service.retry(OWNER, ID, path() + "/retry",
                            "retry-routing-" + routed.hashCode(), bytes(routed)));
            assertEquals(422, routing.status());
            assertEquals("ROUTING_NOT_SUPPORTED", routing.code());
        }
        assertEquals(0, store.mutationReservations);
    }

    @Test
    void exactQuestionUtf8LimitSucceedsAndOneByteOverFailsWithoutReservation() {
        String exactId = "223e4567-e89b-42d3-a456-426614174000";
        ArchiveMutationResult exact = service.create(OWNER, exactId,
                "/archive/v1/me/questions/" + exactId, "exact-limit", body("x".repeat(8192)));
        assertEquals(202, exact.status());
        int reservations = store.mutationReservations;
        String overId = "323e4567-e89b-42d3-a456-426614174000";
        ArchivePersonalDataException over = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, overId, "/archive/v1/me/questions/" + overId,
                        "over-limit", body("x".repeat(8193))));
        assertEquals(422, over.status());
        assertEquals("INVALID_REQUEST_JSON", over.code());
        assertEquals(reservations, store.mutationReservations);
    }

    @Test
    void exactOwnerConcealmentCanonicalIdAndCanonicalPathAreFailClosed() {
        service.create(OWNER, ID, path(), "owner-key", body("x"));
        ArchivePersonalDataException foreign = assertThrows(ArchivePersonalDataException.class,
                () -> service.get(FOREIGN_CLIENT, ID));
        assertEquals(404, foreign.status());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", foreign.code());
        assertEquals(404, assertThrows(ArchivePersonalDataException.class,
                () -> service.get(OWNER, ID.toUpperCase())).status());
        assertEquals(404, assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, "水", "/archive/v1/me/questions/水", "key", body("x"))).status());
        assertEquals(404, assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, ID, path() + "/", "path-key", body("x"))).status());
    }

    @Test
    void completedReplayPrecedesProviderAvailabilityButNewUnavailableMutationIs503AndZeroWrite() {
        ArchiveMutationResult first = service.create(OWNER, ID, path(), "provider-replay", body("x"));
        ArchiveQuestionProvider unavailable = new ArchiveQuestionProvider() {
            @Override public boolean available() { return false; }
            @Override public Answer answer(Request request) { throw new AssertionError(); }
        };
        ArchiveQuestionServiceImpl unavailableService = service(unavailable);
        ArchiveMutationResult replay = unavailableService.create(OWNER, ID, path(), "provider-replay", body("x"));
        assertArrayEquals(first.body(), replay.body());
        assertTrue(replay.replayed());

        String second = "223e4567-e89b-42d3-a456-426614174000";
        ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                () -> unavailableService.create(OWNER, second, "/archive/v1/me/questions/" + second,
                        "provider-new", body("x")));
        assertEquals(503, failure.status());
        assertEquals("QUESTION_PROVIDER_UNAVAILABLE", failure.code());
        assertEquals(1, store.questionInserts);
    }

    @Test
    void exactOwnerRateLimitReturns429AndRollsBackTwentyFirstReservation() {
        for (int index = 0; index < 20; index++) {
            String id = String.format("00000000-0000-4000-8000-%012x", index);
            service.create(OWNER, id, "/archive/v1/me/questions/" + id, "rate-" + index, body("x"));
        }
        int reservations = store.mutationReservations;
        String blocked = "00000000-0000-4000-8000-000000000020";
        ArchivePersonalDataException failure = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(OWNER, blocked, "/archive/v1/me/questions/" + blocked,
                        "rate-blocked", body("x")));
        assertEquals(429, failure.status());
        assertEquals("QUESTION_RATE_LIMITED", failure.code());
        assertEquals(20, store.questionInserts);
        assertEquals(reservations, store.mutationReservations);
    }

    @Test
    void retryRequiresFailedRetryableCasAndVersionSequenceExhaustionRollsBackMutationAndEvent() {
        service.create(OWNER, ID, path(), "retry-base", body("x"));
        QuestionRecord current = store.findQuestion(OWNER, ID, false);
        store.setQuestion(OWNER, failed(current, 5, 7, "QUESTION_PROVIDER_UNAVAILABLE"));
        OutboxRecord outbox = store.findOutbox(OWNER, ID, false);
        store.setOutbox(OWNER, new OutboxRecord(outbox.rowId(), ID, "WAITING_RETRY", 1, 1,
                outbox.publishedSequence(), NOW, null, "QUESTION_PROVIDER_UNAVAILABLE", outbox.createdAt(), NOW));
        ArchiveMutationResult retried = service.retry(OWNER, ID, path() + "/retry", "retry-key",
                bytes("{\"expectedVersion\":\"5\"}"));
        assertEquals(202, retried.status());
        assertEquals("6", service.get(OWNER, ID).version());
        assertEquals("8", service.get(OWNER, ID).currentSequence());
        assertEquals("QUEUED", service.get(OWNER, ID).status());
        ArchiveMutationResult replay = service.retry(OWNER, ID, path() + "/retry", "retry-key",
                bytes("{ \"expectedVersion\" : \"5\" }"));
        assertArrayEquals(retried.body(), replay.body());

        QuestionRecord queued = store.findQuestion(OWNER, ID, false);
        store.setQuestion(OWNER, failed(queued, Long.MAX_VALUE, queued.currentSequence(), "X"));
        store.setOutbox(OWNER, new OutboxRecord(outbox.rowId(), ID, "WAITING_RETRY", 1, 1,
                store.findOutbox(OWNER, ID, false).publishedSequence(), NOW, null, "X", outbox.createdAt(), NOW));
        int beforeEvents = store.eventInserts;
        ArchivePersonalDataException exhausted = assertThrows(ArchivePersonalDataException.class,
                () -> service.retry(OWNER, ID, path() + "/retry", "retry-exhaust",
                        bytes("{\"expectedVersion\":\"" + Long.MAX_VALUE + "\"}")));
        assertEquals("VERSION_EXHAUSTED", exhausted.code());
        assertEquals(beforeEvents, store.eventInserts);
        assertEquals(Long.MAX_VALUE, store.findQuestion(OWNER, ID, false).version());

        QuestionRecord versionExhausted = store.findQuestion(OWNER, ID, false);
        store.setQuestion(OWNER, failed(versionExhausted, 10, Long.MAX_VALUE, "X"));
        OutboxRecord waiting = store.findOutbox(OWNER, ID, false);
        store.setOutbox(OWNER, new OutboxRecord(waiting.rowId(), ID, "WAITING_RETRY", 1, 1,
                waiting.publishedSequence(), NOW, null, "X", waiting.createdAt(), NOW));
        int beforeSequenceEvents = store.eventInserts;
        int beforeSequenceReservations = store.mutationReservations;
        ArchivePersonalDataException sequenceExhausted = assertThrows(ArchivePersonalDataException.class,
                () -> service.retry(OWNER, ID, path() + "/retry", "sequence-exhaust",
                        bytes("{\"expectedVersion\":\"10\"}")));
        assertEquals(409, sequenceExhausted.status());
        assertEquals("VERSION_EXHAUSTED", sequenceExhausted.code());
        assertEquals(beforeSequenceEvents, store.eventInserts);
        assertEquals(beforeSequenceReservations, store.mutationReservations);
        assertEquals(Long.MAX_VALUE, store.findQuestion(OWNER, ID, false).currentSequence());
        assertEquals(10, store.findQuestion(OWNER, ID, false).version());
    }

    private ArchiveQuestionServiceImpl service(ArchiveQuestionProvider provider) {
        return new ArchiveQuestionServiceImpl(store, content, transactions, provider, delivery,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private ArchivePersonalDataStore.ContentPoint point(String id, int ordinal, String text) {
        byte[] bytes = bytes(text);
        return new ArchivePersonalDataStore.ContentPoint(EDITION, MANIFEST, "CHAPTER", BLOCK, 1,
                id, ordinal, text, bytes.length, ArchiveEtags.sha256(bytes));
    }
    private QuestionRecord failed(QuestionRecord row, long version, long sequence, String code) {
        return ArchiveQuestionServiceImpl.change(row, "FAILED_RETRYABLE", row.answer(), row.retryCount(),
                code, version, sequence, NOW, null);
    }
    private String path() { return "/archive/v1/me/questions/" + ID; }
    private byte[] body(String question) { return bytes("{\"question\":\"" + question + "\",\"anchor\":" + anchor + "}"); }
    private String hash(String value) { return ArchiveEtags.sha256(bytes(value)); }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
