package cn.jia.chat.service;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Message-origin revision DATA. A valid basis/digest is never a task grant or user acceptance. */
public final class ChatDeliveryRevisionContract {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> BASIS = Set.of("taskId", "taskVersion", "deliveryId", "deliveryVersion",
            "batchSha256", "assignmentRevision", "sourceMessageId", "sourceRequestId", "items");
    private static final Set<String> ITEM = Set.of("artifactId", "artifactVersion", "contentHash", "purpose", "displayRole");
    private ChatDeliveryRevisionContract() { }

    public record Item(String artifactId, String artifactVersion, String contentHash, String purpose, String displayRole) {
        public Item {
            id(artifactId); decimal(artifactVersion, true); hash(contentHash); text(purpose, false);
            if (displayRole == null || !Set.of("RESULT", "MANIFEST").contains(displayRole)) throw invalid();
        }
    }
    public record Basis(String taskId, String taskVersion, String deliveryId, String deliveryVersion,
            String batchSha256, String assignmentRevision, String sourceMessageId, String sourceRequestId, List<Item> items) {
        public Basis {
            id(taskId); decimal(taskVersion, false); id(deliveryId); decimal(deliveryVersion, false); hash(batchSha256);
            decimal(assignmentRevision, true); decimal(sourceMessageId, true); id(sourceRequestId);
            if (items == null || items.isEmpty()) throw invalid();
            Set<String> ids = new HashSet<>(); boolean hasResult = false;
            for (Item item : items) {
                if (item == null || !ids.add(item.artifactId())) throw invalid();
                hasResult |= "RESULT".equals(item.displayRole());
            }
            if (!hasResult || !batch(taskId, deliveryId, deliveryVersion, items).equals(batchSha256)) throw invalid();
            items = List.copyOf(items);
        }
    }
    public record Revision(String instruction, List<String> targetArtifactIds) {
        public Revision {
            text(instruction, true);
            if (targetArtifactIds == null || targetArtifactIds.isEmpty()) throw invalid();
            Set<String> ids = new HashSet<>();
            for (String id : targetArtifactIds) { id(id); if (!ids.add(id)) throw invalid(); }
            targetArtifactIds = List.copyOf(targetArtifactIds);
        }
    }
    public static Basis basisJson(String raw) { return basis(parse(raw)); }
    public static Basis basis(Object raw) { return basis(JSON.valueToTree(raw)); }
    public static Basis basis(JsonNode raw) {
        // JSON null means no current batch at admission. Missing/invalid data is NOT null.
        if (raw != null && raw.isNull()) return null;
        exact(raw, BASIS);
        JsonNode values = raw.get("items"); if (!values.isArray()) throw invalid();
        List<Item> items = new ArrayList<>();
        for (JsonNode item : values) {
            exact(item, ITEM);
            items.add(new Item(string(item,"artifactId"), string(item,"artifactVersion"),string(item,"contentHash"),
                    string(item,"purpose"),string(item,"displayRole")));
        }
        return new Basis(string(raw,"taskId"),string(raw,"taskVersion"),string(raw,"deliveryId"),
                string(raw,"deliveryVersion"),string(raw,"batchSha256"),string(raw,"assignmentRevision"),
                string(raw,"sourceMessageId"),string(raw,"sourceRequestId"),items);
    }
    public static Revision revision(Object raw, Basis basis) { return revision(JSON.valueToTree(raw), basis); }
    public static Revision revision(JsonNode raw, Basis basis) {
        if (basis == null) throw invalid();
        exact(raw, Set.of("instruction", "targetArtifactIds"));
        if (!raw.get("targetArtifactIds").isArray()) throw invalid();
        List<String> ids = new ArrayList<>();
        for (var id : raw.get("targetArtifactIds")) { if (!id.isTextual()) throw invalid(); ids.add(id.textValue()); }
        Revision revision = new Revision(string(raw,"instruction"), ids);
        Set<String> available = new HashSet<>();
        basis.items().stream().filter(item -> "RESULT".equals(item.displayRole())).forEach(item -> available.add(item.artifactId()));
        if (!available.containsAll(revision.targetArtifactIds())) throw invalid();
        return revision;
    }
    /** Strict v5 revision branch only; full action codec must additionally validate all other branches. */
    public static Revision revisionOutcomeJson(String raw, Basis basis) {
        JsonNode node = parse(raw);
        exact(node, Set.of("schemaVersion","kind","text","clarification","action","deliverable","deliveryRelation","revision"));
        if (!node.get("schemaVersion").isInt() || node.get("schemaVersion").intValue()!=5
                || !"REVISION_REQUEST".equals(string(node,"kind")) || !node.get("clarification").isNull()
                || !node.get("action").isNull() || !node.get("deliveryRelation").isNull()
                || !node.get("deliverable").isBoolean() || node.get("deliverable").booleanValue()) throw invalid();
        text(string(node,"text"),true);
        return revision(node.get("revision"),basis);
    }
    public static String revisionDigest(Basis basis, String parentOutcomeId, String parentFinalDigest, Revision revision) {
        if (basis == null || revision == null) throw invalid();
        // Revalidate constructed records and membership as rigorously as wire input.
        revision(revision,basis); id(parentOutcomeId);
        if (parentFinalDigest == null || !parentFinalDigest.matches("sha256:[0-9a-f]{64}")) throw invalid();
        List<String> targets = new ArrayList<>(revision.targetArtifactIds());
        targets.sort(ChatDeliveryRevisionContract::utf8Compare);
        return ChatDeliveryReviewContract.sha(ChatDeliveryReviewContract.json(List.of("hall-message-review-v1",basis.taskId(),
                basis.sourceRequestId(),basis.sourceMessageId(),parentOutcomeId,parentFinalDigest,basis.deliveryId(),
                basis.deliveryVersion(),basis.batchSha256(),targets,revision.instruction())));
    }
    public static String batch(String task, String delivery, String version, List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort((a,b) -> utf8Compare(a.artifactId(),b.artifactId()));
        return ChatDeliveryReviewContract.sha(ChatDeliveryReviewContract.json(List.of("hall-delivery-batch-v1", task, delivery, version,
                sorted.stream().map(i -> List.of(i.artifactId(),i.artifactVersion(),i.contentHash(),i.purpose())).toList())));
    }
    public static java.util.Map<String,Object> basisMap(Basis basis) {
        if(basis==null)return null;
        return java.util.Map.of("taskId",basis.taskId(),"taskVersion",basis.taskVersion(),"deliveryId",basis.deliveryId(),
                "deliveryVersion",basis.deliveryVersion(),"batchSha256",basis.batchSha256(),"assignmentRevision",basis.assignmentRevision(),
                "sourceMessageId",basis.sourceMessageId(),"sourceRequestId",basis.sourceRequestId(),"items",basis.items().stream()
                .map(i->java.util.Map.of("artifactId",i.artifactId(),"artifactVersion",i.artifactVersion(),"contentHash",i.contentHash(),
                        "purpose",i.purpose(),"displayRole",i.displayRole())).toList());
    }
    public static String json(Basis basis) { return CanonicalContextJson.write(basisMap(basis)); }
    public static String sha(Basis basis) { return ChatDeliveryReviewContract.sha(json(basis)); }
    private static JsonNode parse(String raw) {
        if (raw == null) throw invalid();
        try { return JSON.readTree(raw); } catch (Exception failure) { throw invalid(); }
    }
    private static void exact(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject() || node.size()!=keys.size() || keys.stream().anyMatch(k->!node.has(k))) throw invalid();
    }
    private static String string(JsonNode node,String key) { if (!node.get(key).isTextual()) throw invalid(); return node.get(key).textValue(); }
    private static void id(String id) { try { ChatDeliveryReviewContract.id(id,100); } catch (RuntimeException failure) { throw invalid(); } }
    private static void hash(String hash) { if (hash==null || !hash.matches("[0-9a-f]{64}")) throw invalid(); }
    private static void decimal(String value,boolean positive) {
        if (value==null || !value.matches(positive?"[1-9][0-9]*":"0|[1-9][0-9]*")) throw invalid();
        try { Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(); }
    }
    private static int utf8Compare(String a,String b) { return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)); }
    private static void text(String value,boolean nonblank) {
        if (value==null || (nonblank && value.isBlank())) throw invalid();
        for(int i=0;i<value.length();i++) { char c=value.charAt(i);
            if(c==0)throw invalid();
            if(Character.isHighSurrogate(c)) { if(++i==value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid(); }
            else if(Character.isLowSurrogate(c)) throw invalid();
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("DELIVERY_REVISION_INVALID"); }
}
