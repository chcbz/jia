package cn.jia.chat.service;

import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.UserAdmissionScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationLibraryServiceTest {
    @SuppressWarnings("unchecked")
    @Test
    void delegatesScopedSearchAndSaveInsideAdmissionCallbacks() {
        ConversationAdmissionService admission = mock(ConversationAdmissionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ConversationLibraryService service = new ConversationLibraryService(admission, repository);
        UserAdmissionScope scope = scope();
        ConversationDataScope admitted = new ConversationDataScope(
                scope.businessScope(), ConversationDataScope.Mode.DOMAIN);
        MemoryDocument document = new MemoryDocument();
        List<MemoryDocument> expected = List.of(document);
        when(admission.read(eq(scope), any())).thenAnswer(invocation ->
                ((Function<ConversationDataScope, List<MemoryDocument>>) invocation.getArgument(1))
                        .apply(admitted));
        when(repository.searchWithConversationBoost(
                admitted, "query", "42", 8, 0.2D)).thenReturn(expected);
        when(admission.write(eq(scope), any())).thenAnswer(invocation ->
                ((Function<ConversationDataScope, Object>) invocation.getArgument(1))
                        .apply(admitted));

        assertEquals(expected, service.search(scope, "query", "42", 8, 0.2D));
        service.save(scope, document);

        verify(repository).searchWithConversationBoost(
                admitted, "query", "42", 8, 0.2D);
        verify(repository).save(admitted, document);
    }

    @Test
    void exposesReadOnlyAndWriteTransactionBoundaries() throws Exception {
        Method search = ConversationLibraryService.class.getMethod(
                "search", UserAdmissionScope.class, String.class, String.class,
                int.class, double.class);
        Method save = ConversationLibraryService.class.getMethod(
                "save", UserAdmissionScope.class, MemoryDocument.class);
        Transactional read = search.getAnnotation(Transactional.class);
        Transactional write = save.getAnnotation(Transactional.class);
        assertNotNull(read);
        assertNotNull(write);
        assertEquals(true, read.readOnly());
        assertFalse(write.readOnly());
        assertFalse(Modifier.isFinal(ConversationLibraryService.class.getModifiers()),
                "Spring must be able to create the class-based transactional proxy");
    }

    private UserAdmissionScope scope() {
        return new UserAdmissionScope(
                new BusinessScope(TenantId.of("1"), DataDomainId.of("cyf"),
                        "owner-a", "web-client"),
                7L, 3L, 5L, 11L, 13L);
    }
}
