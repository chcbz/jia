package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatDeliveryRevisionContractTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final JsonNode FIXTURE=fixture();
    static JsonNode fixture() {
        try(var stream=ChatDeliveryRevisionContractTest.class.getResourceAsStream("/contracts/juyiting-ui-workflow.json")) {
            assertNotNull(stream);return JSON.readTree(stream);
        }catch(Exception failure){throw new AssertionError(failure);}
    }
    static ChatDeliveryRevisionContract.Basis basis() {
        var context=FIXTURE.get("contextResponse").get("data");var batch=context.get("currentDelivery");
        List<ChatDeliveryRevisionContract.Item> items=new ArrayList<>();
        for(var i:batch.get("items"))items.add(new ChatDeliveryRevisionContract.Item(i.get("artifactId").asText(),i.get("artifactVersion").asText(),
                i.get("contentHash").asText(),i.get("purpose").asText(),i.get("displayRole").asText()));
        return new ChatDeliveryRevisionContract.Basis(context.get("taskId").asText(),context.get("taskVersion").asText(),
                batch.get("deliveryId").asText(),batch.get("deliveryVersion").asText(),batch.get("batchSha256").asText(),
                context.get("assignmentRevision").asText(),"100","discussion_parent_fixture",items);
    }
    @Test void actualJavaCodecMatchesFrozenRevisionDigestVectorAndStrictV5Branch() {
        var basis=basis();var outcome=FIXTURE.get("v5RevisionOutcome");
        var revision=ChatDeliveryRevisionContract.revisionOutcomeJson(outcome.toString(),basis);
        assertEquals(FIXTURE.get("messageRevisionDigestVector").get("expectedSha256").asText(),
                ChatDeliveryRevisionContract.revisionDigest(basis,"parent_outcome_fixture","sha256:"+"1".repeat(64),revision));
        assertEquals(basis,ChatDeliveryRevisionContract.basisJson(ChatDeliveryRevisionContract.json(basis)));
        assertTrue(ChatDeliveryRevisionContract.json(basis).contains("正式文本"));
    }
    @Test void explicitNoBatchIsDifferentFromMissingOrCorruptSnapshot() {
        assertNull(ChatDeliveryRevisionContract.basisJson("null"));assertEquals("null",ChatDeliveryRevisionContract.json(null));
        for(String raw:List.of("", "{}", "[]", "null true", "{\"taskId\":\"a\",\"taskId\":\"b\"}"))
            assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.basisJson(raw),raw);
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.basis((JsonNode)null));
    }
    @Test void requiresCompleteBatchIncludingManifestAndExactHash() {
        var b=basis();
        assertThrows(IllegalArgumentException.class,()->new ChatDeliveryRevisionContract.Basis(b.taskId(),b.taskVersion(),b.deliveryId(),b.deliveryVersion(),
                b.batchSha256(),b.assignmentRevision(),b.sourceMessageId(),b.sourceRequestId(),b.items().stream().filter(i->"RESULT".equals(i.displayRole())).toList()));
        var duplicate=new ArrayList<>(b.items());duplicate.add(b.items().getFirst());
        assertThrows(IllegalArgumentException.class,()->new ChatDeliveryRevisionContract.Basis(b.taskId(),b.taskVersion(),b.deliveryId(),b.deliveryVersion(),
                b.batchSha256(),b.assignmentRevision(),b.sourceMessageId(),b.sourceRequestId(),duplicate));
        assertThrows(UnsupportedOperationException.class,()->b.items().clear());
    }
    @Test void rejectsManifestUnknownEmptyOrDuplicateTargetsAndMissingBasis() {
        for(var ids:List.of(List.of("artifact_manifest"),List.of("other"),List.of("artifact_text","artifact_text"),List.<String>of())) {
            assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.revision(Map.of("instruction","调整","targetArtifactIds",ids),basis()));
        }
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.revision(Map.of("instruction","调整","targetArtifactIds",List.of("artifact_text")),null));
    }
    @Test void constructorAndWireHaveIdenticalMembershipChecks() {
        var revision=new ChatDeliveryRevisionContract.Revision("调整",List.of("artifact_manifest"));
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.revisionDigest(basis(),"parent","sha256:"+"1".repeat(64),revision));
    }
    @Test void rejectsMalformedDecimalsAndNumericVersionCoercion() {
        for(String invalid:List.of("-1","01","+1","1.0","9223372036854775808"," 1")) {
            ObjectNode raw=(ObjectNode)JSON.valueToTree(basis());raw.put("taskVersion",invalid);
            assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.basis(raw),invalid);
        }
        ObjectNode numeric=(ObjectNode)JSON.valueToTree(basis());numeric.put("taskVersion",7);
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.basis(numeric));
        ObjectNode zero=(ObjectNode)JSON.valueToTree(basis());zero.put("sourceMessageId","0");
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.basis(zero));
    }
    @Test void preservesVersionsBeyondJsSafeIntegerWithoutRounding() {
        ObjectNode raw=(ObjectNode)JSON.valueToTree(basis());raw.put("taskVersion","9223372036854775807");
        var b=ChatDeliveryRevisionContract.basis(raw);assertEquals("9223372036854775807",b.taskVersion());
        assertTrue(ChatDeliveryRevisionContract.json(b).contains("\"9223372036854775807\""));
    }
    @Test void rejectsUnknownBasisAndNestedFields() {
        ObjectNode raw=(ObjectNode)JSON.valueToTree(basis());raw.put("ownerJiacn","forged");
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.basis(raw));
        ObjectNode nested=(ObjectNode)JSON.valueToTree(basis());((ObjectNode)nested.get("items").get(0)).put("url","forged");
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.basis(nested));
    }
    @Test void revisionOutcomeRejectsWrongVersionUnknownKeysAndConflictingUnionBranches() {
        for(String field:List.of("schemaVersion","deliverable","action","clarification","deliveryRelation","kind","revision")) {
            ObjectNode value=(ObjectNode)FIXTURE.get("v5RevisionOutcome").deepCopy();
            switch(field) {
                case "schemaVersion" -> value.put(field,4);
                case "deliverable" -> value.put(field,true);
                case "kind" -> value.put(field,"ANSWER");
                default -> value.put(field,"forged");
            }
            assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.revisionOutcomeJson(value.toString(),basis()),field);
        }
        ObjectNode extra=(ObjectNode)FIXTURE.get("v5RevisionOutcome").deepCopy();extra.put("deliveryId","forged");
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.revisionOutcomeJson(extra.toString(),basis()));
    }
    @Test void revisionRejectsDuplicateJsonKeysTrailingInputAndMalformedUnicode() {
        String raw=FIXTURE.get("v5RevisionOutcome").toString();
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.revisionOutcomeJson(raw+" {}",basis()));
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.revisionOutcomeJson(raw.replace("\"schemaVersion\":5","\"schemaVersion\":5,\"schemaVersion\":5"),basis()));
        assertThrows(IllegalArgumentException.class,()->new ChatDeliveryRevisionContract.Revision("\uD800",List.of("artifact_text")));
        assertThrows(IllegalArgumentException.class,()->new ChatDeliveryRevisionContract.Revision("  \n",List.of("artifact_text")));
    }
    @Test void noDigestNormalizesInstructionOrProvenance() {
        var b=basis();var r=new ChatDeliveryRevisionContract.Revision("调整\n保留",List.of("artifact_text"));
        String digest=ChatDeliveryRevisionContract.revisionDigest(b,"parent","sha256:"+"1".repeat(64),r);
        assertNotEquals(digest,ChatDeliveryRevisionContract.revisionDigest(b,"parent","sha256:"+"1".repeat(64),new ChatDeliveryRevisionContract.Revision("调整 保留",r.targetArtifactIds())));
        var other=new ChatDeliveryRevisionContract.Basis(b.taskId(),b.taskVersion(),b.deliveryId(),b.deliveryVersion(),b.batchSha256(),b.assignmentRevision(),"101",b.sourceRequestId(),b.items());
        assertNotEquals(digest,ChatDeliveryRevisionContract.revisionDigest(other,"parent","sha256:"+"1".repeat(64),r));
        assertThrows(IllegalArgumentException.class,()->ChatDeliveryRevisionContract.revisionDigest(b,"parent","1".repeat(64),r));
    }
    @Test void canonicalSnapshotContainsOnlyPrimitivesAndRemainsUtf8Stable() {
        String canonical=CanonicalContextJson.write(Map.of("reviewBasis",ChatDeliveryRevisionContract.basisMap(basis())));
        assertTrue(canonical.contains("正式文本"));assertFalse(canonical.contains("\\u6b63"));
        assertArrayEquals(canonical.getBytes(StandardCharsets.UTF_8),new String(canonical.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
    }
}
