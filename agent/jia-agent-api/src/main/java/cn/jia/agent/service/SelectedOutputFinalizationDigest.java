package cn.jia.agent.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Canonical, length-prefixed immutable selected-output request digest shared by Chat and Agent. */
public final class SelectedOutputFinalizationDigest {
    private SelectedOutputFinalizationDigest() { }

    /** An actual persisted final message and its immutable server snapshot, never an execution alias. */
    public record MessageSource(String turnId, String messageId, String snapshotId, String finalDigest) { }

    public record Selection(String requestId, String stepId, String outputId,
            String sha256, String title, String purpose, MessageSource messageSource) {
        public Selection(String requestId, String stepId, String outputId, String sha256, String title, String purpose) {
            this(requestId, stepId, outputId, sha256, title, purpose, null);
        }
    }

    public static String request(String taskId, long expectedTaskVersion,
            long expectedAssignmentRevision, String conversationId, String summary,
            List<Selection> selections) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            put(digest, "MMD_SELECTED_OUTPUT_FINALIZATION_V1");
            put(digest, taskId);
            put(digest, expectedTaskVersion);
            put(digest, expectedAssignmentRevision);
            put(digest, conversationId);
            put(digest, summary);
            put(digest, selections == null ? -1L : selections.size());
            if (selections != null) {
                for (Selection selection : selections) {
                    if (selection == null) {
                        put(digest, (String) null);
                        continue;
                    }
                    put(digest, selection.requestId());
                    put(digest, selection.stepId());
                    put(digest, selection.outputId());
                    put(digest, selection.sha256());
                    put(digest, selection.title());
                    put(digest, selection.purpose());
                    if (selection.messageSource() != null) {
                        put(digest, "COMPLETED_MESSAGE");
                        put(digest, selection.messageSource().turnId());
                        put(digest, selection.messageSource().messageId());
                        put(digest, selection.messageSource().snapshotId());
                        put(digest, selection.messageSource().finalDigest());
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void put(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void put(MessageDigest digest, String value) {
        if (value == null) {
            put(digest, -1L);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        put(digest, bytes.length);
        digest.update(bytes);
    }
}
