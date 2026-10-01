package cn.jia.chat.archive.maintenance.entry;

import cn.jia.chat.archive.maintenance.dto.ArchiveCapabilitiesDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveMaintenanceRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveMaintenanceRequestResultDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveNewWorkRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveJobDTO;
import cn.jia.chat.archive.maintenance.model.ArchiveRequestContext;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.publisher.Flux;

/** Builds a fresh archive-only client. It never clones the application's global ChatClient. */
@Component
public class ArchiveRestrictedChatClientFactory {
    public static final List<String> TOOL_NAMES = List.of(
            "getArchiveMaintenanceContext",
            "requestArchiveMaintenance",
            "getArchiveMaintenanceJob");

    private final ChatModel chatModel;
    private final ArchiveMaintenanceService service;

    public ArchiveRestrictedChatClientFactory(ChatModel chatModel,
            ArchiveMaintenanceService service) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.service = Objects.requireNonNull(service, "service");
    }

    public Session create(ArchiveRequestContext context) {
        Objects.requireNonNull(context, "context");
        RestrictedTools tools = new RestrictedTools(service, context);
        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        List<String> names = java.util.Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .sorted().toList();
        if (!names.equals(TOOL_NAMES.stream().sorted().toList())) {
            throw new IllegalStateException("Restricted archive tool set changed");
        }
        ChatClient client = ChatClient.builder(new RestrictedChatModel(chatModel))
                .defaultTools((Object[]) callbacks)
                .build();
        return new Session(client, List.copyOf(names), tools);
    }

    public record Session(ChatClient client, List<String> toolNames, RestrictedTools tools) { }

    /** Server-owned rendering input; model text is never an archive receipt. */
    public record AuthoritativeReceipt(
            String authority,
            boolean terminal,
            String jobId,
            String state,
            String readiness,
            String nextAction) {
        static AuthoritativeReceipt unconfirmed() {
            return new AuthoritativeReceipt("UNCONFIRMED", false, null,
                    "AUTHORITATIVE_TOOL_RESULT_REQUIRED", null, "OPEN_MANAGEMENT_ENTRY");
        }
    }

    /**
     * Prevents the application model's default/global tools and tool context from being
     * inherited while retaining ordinary model sampling options. Spring AI only applies
     * ChatClient default tools when the model options expose a tool-calling builder.
     */
    private static final class RestrictedChatModel implements ChatModel {
        private final ChatModel delegate;

        private RestrictedChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return delegate.call(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return delegate.stream(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return restrictedOptions(delegate.getOptions());
        }
    }

    private static ToolCallingChatOptions restrictedOptions(ChatOptions raw) {
        Objects.requireNonNull(raw, "chatModel options");
        ChatOptions.Builder<?> rawBuilder = raw.mutate();
        if (rawBuilder instanceof ToolCallingChatOptions.Builder<?> toolBuilder) {
            return toolBuilder.toolCallbacks(List.of())
                    .toolContext((Map<String, Object>) null).build();
        }
        return ToolCallingChatOptions.builder()
                .model(raw.getModel())
                .frequencyPenalty(raw.getFrequencyPenalty())
                .maxTokens(raw.getMaxTokens())
                .presencePenalty(raw.getPresencePenalty())
                .stopSequences(raw.getStopSequences())
                .temperature(raw.getTemperature())
                .topK(raw.getTopK())
                .topP(raw.getTopP())
                .toolCallbacks(List.of())
                .toolContext((Map<String, Object>) null)
                .build();
    }

    public static final class RestrictedTools {
        private static final Set<String> TERMINAL_STATES = Set.of("PUBLISHED", "CANCELLED", "FAILED");
        private final ArchiveMaintenanceService service;
        private final ArchiveRequestContext context;
        private final AtomicReference<String> requestedJobId = new AtomicReference<>();
        private final AtomicReference<AuthoritativeReceipt> latestReceipt =
                new AtomicReference<>(AuthoritativeReceipt.unconfirmed());

        RestrictedTools(ArchiveMaintenanceService service, ArchiveRequestContext context) {
            this.service = service;
            this.context = context;
        }

        @Tool(name = "getArchiveMaintenanceContext",
                description = "Get authorized archive maintenance actions and readiness for the confirmed collection")
        public ArchiveCapabilitiesDTO getArchiveMaintenanceContext(
                @ToolParam(description = "Archive collection ID") String collectionId) {
            requireConfirmedCollection(collectionId);
            return service.capabilities(context.actorScope(), collectionId);
        }

        @Tool(name = "requestArchiveMaintenance",
                description = "Request the confirmed archive maintenance operation without widening source or publication authority")
        public ArchiveMaintenanceRequestResultDTO requestArchiveMaintenance(
                @ToolParam(description = "Archive collection ID") String collectionId,
                @ToolParam(description = "ADD_WORK or REVISE_WORK") String operation,
                @ToolParam(description = "Canonical key for ADD_WORK", required = false) String canonicalKey,
                @ToolParam(description = "Title for ADD_WORK", required = false) String title,
                @ToolParam(description = "Language for ADD_WORK", required = false) String language,
                @ToolParam(description = "Existing work ID for REVISE_WORK", required = false) String workId,
                @ToolParam(description = "Confirmed private source snapshot ID") String sourceId,
                @ToolParam(description = "MANUAL or AUTO, within the confirmed ceiling") String requestedPublicationMode) {
            ArchiveNewWorkRequest newWork = "ADD_WORK".equals(operation)
                    ? new ArchiveNewWorkRequest(canonicalKey, title, language) : null;
            ArchiveMaintenanceRequestResultDTO result = service.request(context,
                    new ArchiveMaintenanceRequest(collectionId, operation, newWork, workId,
                            sourceId, requestedPublicationMode));
            if (result.job() != null) {
                bindRequestedJob(result.job().jobId());
                latestReceipt.set(receipt(result.job(), result.readiness(), result.nextAction()));
            }
            return result;
        }

        @Tool(name = "getArchiveMaintenanceJob",
                description = "Get an authoritative archive maintenance job visible to the current authenticated owner")
        public ArchiveJobDTO getArchiveMaintenanceJob(
                @ToolParam(description = "Archive maintenance job ID") String jobId) {
            String expected = requestedJobId.get();
            if (expected == null || !expected.equals(jobId)) {
                throw new ArchiveMaintenanceException(403, "ARCHIVE_REQUEST_POLICY_VIOLATION",
                        "Archive job is outside this confirmed request session");
            }
            ArchiveJobDTO job = service.getJob(context.actorScope(), jobId);
            latestReceipt.set(receipt(job, job.waitReason(),
                    TERMINAL_STATES.contains(job.state()) ? "NONE" : "GET_JOB"));
            return job;
        }

        public AuthoritativeReceipt authoritativeReceipt() {
            return latestReceipt.get();
        }

        private void bindRequestedJob(String jobId) {
            String existing = requestedJobId.get();
            if (existing == null) {
                if (!requestedJobId.compareAndSet(null, jobId)) {
                    existing = requestedJobId.get();
                } else {
                    existing = jobId;
                }
            }
            if (!Objects.equals(existing, jobId)) {
                throw new ArchiveMaintenanceException(409, "ARCHIVE_RESULT_CONFLICT",
                        "Archive tool session resolved more than one job");
            }
        }

        private AuthoritativeReceipt receipt(ArchiveJobDTO job, String readiness,
                String nextAction) {
            boolean terminal = TERMINAL_STATES.contains(job.state());
            return new AuthoritativeReceipt(
                    terminal ? "AUTHORITATIVE_TERMINAL" : "AUTHORITATIVE_NON_TERMINAL",
                    terminal, job.jobId(), job.state(), readiness,
                    terminal ? "NONE" : nextAction);
        }

        private void requireConfirmedCollection(String collectionId) {
            if (!Objects.equals(context.confirmedPolicyRef().collectionId(), collectionId)) {
                throw new ArchiveMaintenanceException(403, "ARCHIVE_REQUEST_POLICY_VIOLATION",
                        "Archive collection is outside the confirmed request");
            }
        }
    }
}
