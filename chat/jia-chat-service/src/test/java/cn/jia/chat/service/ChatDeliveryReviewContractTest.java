package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatDeliveryReviewContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String BATCH = "a7847d54b1184c597c29084c5be98aaddea12660cc19e621d9b916ae0fccf4e3";
    static AgentTaskFormalDeliveryViewDTO batch() {
        var batch = new AgentTaskFormalDeliveryViewDTO(); batch.setTaskId("task_fixture");
        batch.setDeliveryId("delivery_fixture"); batch.setDeliveryVersion(0L); batch.setTaskVersion(7L); batch.setState("submitted");
        var manifest = new AgentTaskFormalDeliveryItemDTO(); manifest.setArtifactId("artifact_manifest"); manifest.setArtifactVersion(1);
        manifest.setContentHash("99d35512273e0bf7ceb7a67b0ec97805a5c6cfb2b30a8852e5c452c6cf6a108f"); manifest.setPurpose("manifest");
        var text = new AgentTaskFormalDeliveryItemDTO(); text.setArtifactId("artifact_text"); text.setArtifactVersion(1);
        text.setContentHash("5c9df06d7bab36422a3204bcf4af382ee5e076269f6dd6dde62c0678d0e0b2bb"); text.setPurpose("正式文本");
        batch.setItems(new ArrayList<>(List.of(text, manifest))); return batch;
    }
    @Test void consumesPublishedCrossLanguageBatchVectorIncludingManifestAndUnicode() {
        var batch = batch(); assertEquals(BATCH, ChatDeliveryReviewContract.batchDigest("task_fixture", batch));
        java.util.Collections.reverse(batch.getItems()); assertEquals(BATCH, ChatDeliveryReviewContract.batchDigest("task_fixture", batch));
        batch.getItems().removeFirst(); assertNotEquals(BATCH, ChatDeliveryReviewContract.batchDigest("task_fixture", batch));
    }
    @Test void rejectsDuplicateArtifactsAndInvalidUnicodeInsteadOfChangingFrozenBytes() {
        var duplicate = batch(); duplicate.getItems().add(duplicate.getItems().getFirst());
        assertThrows(ChatDeliveryReviewContract.Conflict.class, () -> ChatDeliveryReviewContract.batchDigest("task_fixture", duplicate));
        var invalid = batch(); invalid.getItems().getFirst().setPurpose("bad\uD800");
        assertThrows(ChatDeliveryReviewContract.Conflict.class, () -> ChatDeliveryReviewContract.batchDigest("task_fixture", invalid));
    }
    @Test void acceptsLongVersionsExactlyAndRejectsNumericLeadingZeroOverflowAndHttpRework() throws Exception {
        var command = new ChatDeliveryReviewContract.Accept("delivery_fixture", Long.MAX_VALUE, 0, 1, BATCH);
        String wire = ChatDeliveryReviewContract.json(ChatDeliveryReviewContract.body(command));
        assertEquals(command, ChatDeliveryReviewContract.parse(JSON.readTree(wire)));
        for (String modified : List.of(wire.replace("\"9223372036854775807\"", "9223372036854775807"),
                wire.replace("\"9223372036854775807\"", "\"01\""), wire.replace("9223372036854775807", "9223372036854775808"),
                wire.replace("ACCEPT", "REQUEST_CHANGES"), wire.replace("\"instruction\":null", "\"instruction\":\"modify\""),
                wire.replace("\"sourceSelectors\":[]", "\"sourceSelectors\":[{}]"), wire.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"owner\":\"other\""))) {
            assertThrows(ChatDeliveryReviewContract.BadRequest.class, () -> ChatDeliveryReviewContract.parse(JSON.readTree(modified)), modified);
        }
        assertNotEquals(ChatDeliveryReviewContract.requestDigest(command), ChatDeliveryReviewContract.requestDigest(
                new ChatDeliveryReviewContract.Accept("delivery_fixture", Long.MAX_VALUE, 1, 1, BATCH)));
    }
}
