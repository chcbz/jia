package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.PersonalWorkspaceViews.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static cn.jia.agent.entity.AssigneeRecommendation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RecommendationMaterialsTest {
    private static final HostingRentHttp.Actor ACTOR = new HostingRentHttp.Actor("user", "0", "client", "owner");
    private final PersonalWorkspaceTaskLinkService links = mock(PersonalWorkspaceTaskLinkService.class);
    private final PersonalWorkspaceService files = mock(PersonalWorkspaceService.class);
    private final RecommendationMaterials subject = new RecommendationMaterials(links, files);
    private static final Scope LINK_SCOPE = new Scope("0", "client", "owner");
    private static final PersonalWorkspaceService.Scope FILE_SCOPE = new PersonalWorkspaceService.Scope("0", "client", "owner");
    private static LinkView link(String id, String role, String state) {
        return new LinkView(id, "task", "file", 1, role, state, 1, 123);
    }
    private static VersionView version(int n, String mime) {
        return new VersionView("file", n, "original.png", mime, 30, "sha" + n, 123, "READY");
    }
    private static FileDetailView detail(String name, long revision, List<VersionView> versions) {
        return new FileDetailView(new FileView("file", "UPLOAD", "USER", name, "document", "ACTIVE",
                revision, 102, 123, null), version(102, "application/pdf"), versions, List.of(), List.of());
    }
    private void single() {
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(List.of(link("r", "INPUT", "ACTIVE")), null));
        when(files.get(FILE_SCOPE, "file")).thenReturn(detail("图片", 1, List.of(version(1, "image/png"))));
    }
    @Test void readsOnlyMetadataAndUsesLinkedMimeNotCurrentFileFamilyOrLatestVersion() {
        single();
        var rows = subject.observe(ACTOR, "task");
        assertEquals("image/png", rows.getFirst().material().contentMimeType());
        assertEquals("图片", rows.getFirst().material().name());
        assertEquals(1, rows.getFirst().material().version());
        assertNull(rows.getFirst().material().summary());
        verify(links).list(LINK_SCOPE, "task", null); verify(files).get(FILE_SCOPE, "file");
        verifyNoMoreInteractions(links, files); // no body, preview, download or write path
    }
    @Test void followsLinkAndVersionPagesWithoutReplacingOldVersion() {
        var first = IntStream.range(0, 50).mapToObj(i -> link("r" + i, "OUTPUT", "ACTIVE")).toList();
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(first, "next"));
        when(links.list(LINK_SCOPE, "task", "next")).thenReturn(new LinkListView(List.of(link("input", "REFERENCE", "ACTIVE")), null));
        var recent = IntStream.rangeClosed(3, 102).mapToObj(i -> version(i, "application/pdf")).toList();
        when(files.get(FILE_SCOPE, "file")).thenReturn(detail("旧图片", 1, recent));
        when(files.versionsPage(FILE_SCOPE, "file", null)).thenReturn(new VersionPage(recent, "old", true));
        when(files.versionsPage(FILE_SCOPE, "file", "old")).thenReturn(new VersionPage(List.of(version(2, "text/plain"), version(1, "image/png")), null, false));
        var rows = subject.observe(ACTOR, "task");
        assertEquals(1, rows.size()); assertEquals("image/png", rows.getFirst().material().contentMimeType());
        verify(files).versionsPage(FILE_SCOPE, "file", "old"); verify(files, times(1)).get(FILE_SCOPE, "file");
    }
    @Test void excludesDetachedAndOutputAndAllowsEmptyMaterials() {
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(List.of(link("a", "INPUT", "DETACHED"), link("b", "OUTPUT", "ACTIVE")), null));
        assertTrue(subject.observe(ACTOR, "task").isEmpty()); verifyNoInteractions(files);
    }
    @Test void missingExactVersionFailsRatherThanInventingOrIgnoringMaterial() {
        single(); when(files.get(FILE_SCOPE, "file")).thenReturn(detail("file", 1, List.of(version(102, "text/plain"))));
        when(files.versionsPage(FILE_SCOPE, "file", null)).thenReturn(new VersionPage(List.of(version(102, "text/plain")), null, false));
        assertEquals(409, assertThrows(Failure.class, () -> subject.observe(ACTOR, "task")).status());
    }
    @Test void renameDetachAndVersionChangesInvalidateObservation() {
        single(); var before = subject.observe(ACTOR, "task");
        when(files.get(FILE_SCOPE, "file")).thenReturn(detail("renamed", 2, List.of(version(1, "image/png"))));
        assertEquals(409, assertThrows(Failure.class, () -> subject.verifyUnchanged(ACTOR, "task", before)).status());
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(List.of(), null));
        assertEquals(409, assertThrows(Failure.class, () -> subject.verifyUnchanged(ACTOR, "task", before)).status());
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(List.of(new LinkView("r", "task", "file", 102, "INPUT", "ACTIVE", 2, 123)), null));
        when(files.get(FILE_SCOPE, "file")).thenReturn(detail("图片", 1, List.of(version(102, "application/pdf"))));
        assertEquals(409, assertThrows(Failure.class, () -> subject.verifyUnchanged(ACTOR, "task", before)).status());
    }
    @Test void metadataFailureIsNotTreatedAsNoAttachments() {
        single(); when(files.get(FILE_SCOPE, "file")).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> subject.observe(ACTOR, "task"));
    }
    @Test void passesEveryFileTypeIncludingUnknownMimeAndNoExtensionWithoutClassification() {
        single();
        for (String filename : List.of("result.png", "report.pdf", "data.csv", "recording.wav", "archive.unknown", "无后缀资料")) {
            var v = new VersionView("file", 1, filename, "application/octet-stream", 30, "sha1", 123, "UNSUPPORTED");
            when(files.get(FILE_SCOPE, "file")).thenReturn(detail(filename, 1, List.of(v)));
            var rows = subject.observe(ACTOR, "task");
            assertEquals(1, rows.size());
            assertEquals(filename, rows.getFirst().material().name());
            assertEquals(filename, rows.getFirst().material().originalFilename());
            assertEquals("application/octet-stream", rows.getFirst().material().contentMimeType());
        }
        verify(files, times(6)).get(FILE_SCOPE, "file");
        verifyNoMoreInteractions(files);
    }

}
