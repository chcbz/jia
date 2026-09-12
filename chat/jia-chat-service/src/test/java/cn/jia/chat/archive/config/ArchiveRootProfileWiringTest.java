package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchiveRootProfileWiringTest {
    @Test
    void rootDevProdAndGreyProfilesRouteArchiveThroughJwtAndRemainDisabled() throws Exception {
        assertProfile("application-dev.properties", List.of(
                "/task/**", "/phrase/**", "/user/**", "/kefu/**", "/tip/**", "/dwz/**",
                "/vote/**", "/chat/**", "/agent/**", "/gift/**", "/wx/**", "/job/**",
                "/sms/**", "/dict/**", "/point/**", "/pvlog/**", "/news/**", "/media/**",
                "/group/**", "/role/**", "/action/**", "/org/**", "/msg/**", "/archive/**"));
        List<String> prodLike = List.of(
                "/task/**", "/phrase/**", "/user/**", "/kefu/**", "/tip/**", "/dwz/**",
                "/vote/**", "/chat/**", "/gift/**", "/wx/**", "/agent/**", "/job/**",
                "/sms/**", "/dict/**", "/point/**", "/pvlog/**", "/news/**", "/media/**",
                "/group/**", "/role/**", "/action/**", "/org/**", "/msg/**", "/archive/**");
        assertProfile("application-prod.properties", prodLike);
        assertProfile("application-grey.properties", prodLike);
    }

    private void assertProfile(String fileName, List<String> expectedUris) throws IOException {
        Path profile = findRepositoryRoot().resolve("starter/src/main/resources").resolve(fileName);
        List<String> lines = Files.readAllLines(profile);
        List<String> uris = new ArrayList<>();
        int disabledCount = 0;
        for (String line : lines) {
            if (line.equals("archive.reader.enabled=false")) disabledCount++;
            String prefix = "oauth.resource.uris[";
            if (line.startsWith(prefix)) {
                int equals = line.indexOf('=');
                int index = Integer.parseInt(line.substring(prefix.length(), line.indexOf(']')));
                assertEquals(uris.size(), index, fileName + " URI numbering");
                uris.add(line.substring(equals + 1));
            }
        }
        assertEquals(expectedUris, uris, fileName);
        assertEquals(1, disabledCount, fileName + " explicit default-off count");
    }

    private Path findRepositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("settings.gradle"))
                    && Files.isDirectory(cursor.resolve("starter/src/main/resources"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Unable to locate API repository root");
    }
}
