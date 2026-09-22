package cn.jia.agent.service.impl;

import cn.jia.agent.dao.HallReadDao;
import cn.jia.agent.entity.HallItemRow;
import cn.jia.agent.service.HallReadService;
import cn.jia.agent.service.HallRequestDraftService.OwnerScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HallReadServiceImplTest {
    private static final OwnerScope SCOPE = new OwnerScope("0", "client-a", "owner-a");
    private HallReadDao dao;
    private HallReadService service;
    @BeforeEach void setUp() {
        dao = mock(HallReadDao.class);
        when(dao.page(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                nullable(Long.class), nullable(String.class), nullable(String.class), anyInt())).thenReturn(List.of());
        service = new HallReadServiceImpl(dao, () -> 1000L);
    }
    @Test void overviewIsIndependentAndExplicitAboutUnknownActionEvidence() {
        service.items(SCOPE, "task", "recent", "changed filter", null);
        var result = service.overview(SCOPE);
        assertEquals(1, result.schemaVersion()); assertEquals(1000L, result.asOf());
        assertEquals("complete", result.sections().get("recent").status());
        assertEquals("partial", result.sections().get("needsAction").status());
        assertEquals("complete", result.sourceStatus().get("needsAction").get("private").status());
        assertEquals("FORMAL_REVIEW_UNAVAILABLE", result.sourceStatus().get("needsAction").get("task").errorCode());
        assertNull(result.sections().get("recent").partitions().get("draft").count());
        for (String kind : List.of("private", "task", "draft")) {
            verify(dao).page("0", "client-a", "owner-a", kind, "recent", "", null, null, null, 21);
            verify(dao).page("0", "client-a", "owner-a", kind, "needsAction", "", null, null, null, 21);
        }
    }
    @Test void failedSourceIsNotReportedAsEmptySuccessAndOtherSourcesSurvive() {
        when(dao.page("0", "client-a", "owner-a", "task", "recent", "", null, null, null, 21))
                .thenThrow(new IllegalStateException("sensitive SQL"));
        var page = service.items(SCOPE, null, null, null, null);
        assertEquals("partial", page.section().status());
        var failed = page.section().partitions().get("task");
        assertEquals("error", failed.status()); assertNull(failed.count());
        assertEquals("HALL_SOURCE_UNAVAILABLE", failed.errorCode());
        assertEquals("complete", page.section().partitions().get("draft").status());
    }
    @Test void allSourceFailuresProduceErrorNotComplete() {
        when(dao.page(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                nullable(Long.class), nullable(String.class), nullable(String.class), anyInt())).thenReturn(null);
        assertEquals("error", service.items(SCOPE, null, null, null, null).section().status());
    }
    @Test void foreignOwnerClientTenantAndInvalidStateRowsFailClosed() {
        for (HallItemRow row : List.of(row("DRAFT", "d", "EDITING", 9).setOwnerJiacn("owner-b"),
                row("DRAFT", "d", "EDITING", 9).setClientId("client-b"),
                row("DRAFT", "d", "EDITING", 9).setTenantId("1"),
                row("DRAFT", "d", "SUBMITTED", 9))) {
            when(dao.page("0", "client-a", "owner-a", "draft", "recent", "", null, null, null, 21))
                    .thenReturn(List.of(row));
            assertEquals("error", service.items(SCOPE, "draft", null, null, null).section().status());
        }
    }
    @Test void sourcePagesUseStableBinaryKeysetAndBindScopeFilterAndView() {
        List<HallItemRow> rows = IntStream.range(0, 21)
                .mapToObj(i -> row("DRAFT", "d" + String.format("%02d", 99-i), "EDITING", 9)).toList();
        when(dao.page("0", "client-a", "owner-a", "draft", "recent", "name", null, null, null, 21)).thenReturn(rows);
        var first = service.items(SCOPE, "draft", "recent", " name ", null).section().partitions().get("draft");
        assertEquals(20, first.items().size()); assertEquals("d80", first.items().getLast().ref().sourceId());
        assertEquals("complete", first.status()); assertNotNull(first.nextCursor());
        service.items(SCOPE, "draft", "recent", "name", first.nextCursor());
        verify(dao).page("0", "client-a", "owner-a", "draft", "recent", "name", 9L, "DRAFT", "d80", 21);
        for (OwnerScope other : List.of(new OwnerScope("0", "other", "owner-a"), new OwnerScope("0", "client-a", "other"))) {
            assertBad(() -> service.items(other, "draft", "recent", "name", first.nextCursor()));
        }
        assertBad(() -> service.items(SCOPE, "draft", "needsAction", "name", first.nextCursor()));
        assertBad(() -> service.items(SCOPE, "draft", "recent", "other", first.nextCursor()));
        assertBad(() -> service.items(SCOPE, "task", "recent", "name", first.nextCursor()));
        assertBad(() -> service.items(SCOPE, "all", "recent", "name", first.nextCursor()));
        assertBad(() -> service.items(SCOPE, "draft", "recent", "name", first.nextCursor() + "="));
    }
    @Test void duplicateOrOutOfOrderRowsAreSourceErrorsRatherThanLossyPagination() {
        var row = row("DRAFT", "d", "EDITING", 9);
        when(dao.page("0", "client-a", "owner-a", "draft", "recent", "", null, null, null, 21))
                .thenReturn(List.of(row, row));
        assertEquals("error", service.items(SCOPE, "draft", null, null, null).section().status());
    }
    @Test void rawPrivateAndTaskStatesAreNotMergedAndQueuedIsNotAnAction() {
        when(dao.page("0", "client-a", "owner-a", "private", "recent", "", null, null, null, 21))
                .thenReturn(List.of(row("PRIVATE_CASE", "c", "QUEUED", 12), row("LEGACY_EXECUTION", "e", "OUTPUT_COMMITTED", 9).setTitle(null)));
        var items = service.items(SCOPE, "private", null, null, null).section().partitions().get("private").items();
        assertEquals("QUEUED", items.getFirst().status().code());
        assertEquals(List.of("OPEN_CASE"), items.getFirst().allowedActions());
        assertEquals("OPEN_EXECUTION", items.getLast().nextAction()); assertNull(items.getLast().title());
        when(dao.page("0", "client-a", "owner-a", "private", "needsAction", "", null, null, null, 21))
                .thenReturn(List.of(row("PRIVATE_CASE", "c", "QUEUED", 12)));
        assertEquals("error", service.items(SCOPE, "private", "needsAction", null, null).section().status());
    }
    @Test void draftArchiveIsUnavailableNotEmptySuccessAndMalformedInputDoesNotQuery() {
        assertEquals("HALL_DRAFT_ARCHIVE_UNAVAILABLE", service.items(SCOPE, "draft", "archive", null, null)
                .section().partitions().get("draft").errorCode());
        assertBad(() -> service.items(SCOPE, "x", null, null, null));
        assertBad(() -> service.items(SCOPE, "draft", "x", null, null));
        assertBad(() -> service.items(SCOPE, "draft", null, "x".repeat(201), null));
        assertBad(() -> service.items(SCOPE, "draft", null, null, "bad cursor"));
        assertBad(() -> service.overview(new OwnerScope("1", "client-a", "owner-a")));
        verifyNoInteractions(dao);
    }
    @Test void privateMarkAndFormalReviewAreAdditiveButNeverPrivateAcceptanceActions() {
        var privateRow = row("LEGACY_EXECUTION","e","OUTPUT_COMMITTED",9)
                .setMarkRevision(2L).setArchived(true).setViewedExecutionId("e").setViewedManifestId("fixed");
        when(dao.page("0","client-a","owner-a","private","archive","",null,null,null,21))
                .thenReturn(List.of(privateRow));
        var item = service.items(SCOPE,"private","archive",null,null).section().partitions().get("private").items().getFirst();
        assertEquals(2,item.personalMark().revision()); assertTrue(item.personalMark().archived());
        assertEquals("fixed",item.personalMark().viewedResultRef().manifestId()); assertNull(item.review());
        service = new HallReadServiceImpl(dao,()->1000L,true);
        var task = row("TASK","task","reviewing",9).setReviewReady(true).setDeliveryId("delivery")
                .setWorkItemId("work").setDeliveryVersion(0L).setTaskVersion(4L);
        when(dao.page("0","client-a","owner-a","task","needsAction","",null,null,null,21)).thenReturn(List.of(task));
        var partition = service.items(SCOPE,"task","needsAction",null,null).section().partitions().get("task");
        assertEquals("complete",partition.status()); item = partition.items().getFirst();
        assertEquals("FORMAL_DELIVERY_SUBMITTED",item.review().code()); assertEquals("4",item.review().taskVersion());
        assertNull(item.personalMark()); assertEquals(List.of("OPEN_TASK"),item.allowedActions());
        task.setReviewReady(false);
        assertEquals("error",service.items(SCOPE,"task","needsAction",null,null).section().status());
    }

    private static HallItemRow row(String type, String id, String state, long time) {
        return new HallItemRow().setTenantId("0").setClientId("client-a").setOwnerJiacn("owner-a")
                .setSourceType(type).setSourceId(id).setTitle("name").setState(state).setUpdatedAt(time)
                .setExecutionId(id).setMarkRevision(0L).setArchived(false);
    }
    private static void assertBad(Runnable call) {
        assertEquals(HallReadService.Reason.BAD_REQUEST,
                assertThrows(HallReadService.Failure.class, call::run).reason());
    }
}
