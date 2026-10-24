package cn.jia.chat.service;

import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.core.tenant.UserAdmissionScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Tenant-admitted boundary for external conversation library reads and writes. */
@Service
public class ConversationLibraryService {
    private final ConversationAdmissionService admissionService;
    private final MemoryRepository memoryRepository;

    public ConversationLibraryService(
            ConversationAdmissionService admissionService,
            MemoryRepository memoryRepository) {
        this.admissionService = Objects.requireNonNull(admissionService, "admissionService");
        this.memoryRepository = Objects.requireNonNull(memoryRepository, "memoryRepository");
    }

    @Transactional(readOnly = true)
    public List<MemoryDocument> search(
            UserAdmissionScope scope, String query, String conversationId,
            int topK, double similarityThreshold) {
        return admissionService.read(scope, admitted ->
                memoryRepository.searchWithConversationBoost(
                        admitted, query, conversationId, topK, similarityThreshold));
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(UserAdmissionScope scope, MemoryDocument document) {
        admissionService.write(scope, admitted -> {
            memoryRepository.save(admitted, document);
            return null;
        });
    }
}
