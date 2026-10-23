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

    /** Fixed historical bytes, not an authorization grant or a browser-writable source selector. */
    public record FormalArtifactSource(String sourceDeliveryId,String sourceArtifactId,int sourceArtifactVersion) {
        public FormalArtifactSource {
            identifier(sourceDeliveryId);identifier(sourceArtifactId);
            if(sourceArtifactVersion<1)throw invalid();
        }
    }

    public record Selection(String requestId, String stepId, String outputId,
            String sha256, String title, String purpose, MessageSource messageSource,FormalArtifactSource formalArtifactSource) {
        public Selection(String requestId, String stepId, String outputId, String sha256, String title, String purpose) {
            this(requestId, stepId, outputId, sha256, title, purpose, null,null);
        }
        public Selection(String requestId,String stepId,String outputId,String sha256,String title,String purpose,MessageSource messageSource) {
            this(requestId,stepId,outputId,sha256,title,purpose,messageSource,null);
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
                    // Historical v1 has no encoding for a formal source. Never silently drop it.
                    if(selection.formalArtifactSource()!=null)throw invalid();
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

    /** V2 producer codec. Promotion stores/manifests must explicitly opt into the same version;
     * the presence of this pure function does not migrate or resume any v1 operation. */
    public static String requestV2(String taskId,long expectedTaskVersion,long expectedAssignmentRevision,
            String conversationId,String summary,List<Selection> selections) {
        identifier(taskId);identifier(conversationId);scalar(summary);
        if(expectedTaskVersion<0 || expectedAssignmentRevision<0 || selections==null || selections.isEmpty())throw invalid();
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            put(digest,"MMD_SELECTED_OUTPUT_FINALIZATION_V2");put(digest,taskId);put(digest,expectedTaskVersion);
            put(digest,expectedAssignmentRevision);put(digest,conversationId);put(digest,summary);put(digest,selections.size());
            var seen=new java.util.HashSet<String>();
            for(Selection selection:selections) {
                String kind=validateV2(selection);
                String identity=switch(kind) {
                    case "OUTPUT" -> "OUTPUT\0"+selection.requestId()+"\0"+selection.stepId()+"\0"+selection.outputId();
                    case "COMPLETED_MESSAGE" -> "COMPLETED_MESSAGE\0"+selection.requestId()+"\0"+selection.messageSource().turnId()+"\0"+selection.messageSource().messageId();
                    case "FORMAL_ARTIFACT" -> "FORMAL_ARTIFACT\0"+selection.formalArtifactSource().sourceDeliveryId()+"\0"+selection.formalArtifactSource().sourceArtifactId()+"\0"+selection.formalArtifactSource().sourceArtifactVersion();
                    default -> throw invalid();
                };
                if(!seen.add(identity))throw invalid();
                put(digest,kind);put(digest,selection.requestId());put(digest,selection.stepId());put(digest,selection.outputId());
                put(digest,selection.sha256());put(digest,selection.title());put(digest,selection.purpose());
                MessageSource message=selection.messageSource();FormalArtifactSource formal=selection.formalArtifactSource();
                put(digest,message==null?null:message.turnId());put(digest,message==null?null:message.messageId());
                put(digest,message==null?null:message.snapshotId());put(digest,message==null?null:message.finalDigest());
                put(digest,formal==null?null:formal.sourceDeliveryId());put(digest,formal==null?null:formal.sourceArtifactId());
                put(digest,formal==null?-1L:formal.sourceArtifactVersion());
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static String validateV2(Selection selection) {
        if(selection==null || selection.sha256()==null || !selection.sha256().matches("[0-9a-f]{64}"))throw invalid();
        scalar(selection.title());scalar(selection.purpose());
        var message=selection.messageSource();var formal=selection.formalArtifactSource();
        if(formal!=null) {
            if(message!=null || selection.requestId()!=null || selection.stepId()!=null || selection.outputId()!=null)throw invalid();
            return "FORMAL_ARTIFACT";
        }
        identifier(selection.requestId());
        if(message!=null) {
            if(selection.stepId()!=null || selection.outputId()!=null)throw invalid();
            identifier(message.turnId());identifier(message.snapshotId());
            if(message.finalDigest()==null || !message.finalDigest().matches("sha256:[0-9a-f]{64}")
                    || message.messageId()==null || !message.messageId().matches("[1-9][0-9]*"))throw invalid();
            try {Long.parseLong(message.messageId());}catch(NumberFormatException malformed) {throw invalid();}
            return "COMPLETED_MESSAGE";
        }
        identifier(selection.stepId());identifier(selection.outputId());return "OUTPUT";
    }
    private static void identifier(String value) {
        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))throw invalid();
    }
    private static void scalar(String value) {
        if(value==null || value.isBlank())throw invalid();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);if(c==0)throw invalid();
            if(Character.isHighSurrogate(c)) {if(++i==value.length() || !Character.isLowSurrogate(value.charAt(i)))throw invalid();}
            else if(Character.isLowSurrogate(c))throw invalid();
        }
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("FINALIZATION_V2_SOURCE_INVALID");}

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
