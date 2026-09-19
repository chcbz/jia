package cn.jia.build.dependency;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production runtime dependency/linkage regression; isolated from testImplementation. */
class PoiProductionRuntimeClasspathTest {
    private static final Pattern POI_JAR = Pattern.compile(
            "^(poi(?:-ooxml(?:-full|-lite|-schemas)?|-scratchpad)?)-(.+)\\.jar$");
    private static final Set<String> REQUIRED_ARTIFACTS =
            Set.of("poi", "poi-ooxml", "poi-ooxml-full");
    private static final Set<String> FORBIDDEN_SCHEMA_ARTIFACTS =
            Set.of("poi-ooxml-lite", "poi-ooxml-schemas");
    private static final List<DocumentProbe> DOCUMENT_PROBES = List.of(
            new DocumentProbe("org.apache.poi.xwpf.usermodel.XWPFDocument", "createParagraph",
                    new Class<?>[0], new Object[0]),
            new DocumentProbe("org.apache.poi.xssf.usermodel.XSSFWorkbook", "createSheet",
                    new Class<?>[] {String.class}, new Object[] {"runtime-probe"}),
            new DocumentProbe("org.apache.poi.xslf.usermodel.XMLSlideShow", "createSlide",
                    new Class<?>[0], new Object[0]));

    @Test
    void productionRuntimeUsesOneAlignedPoiFullSchemaGraph() throws Exception {
        List<Path> runtime = runtimeEntries();
        Map<String, List<PoiArtifact>> artifacts = poiArtifacts(runtime);

        for (String required : REQUIRED_ARTIFACTS) {
            assertEquals(1, artifacts.getOrDefault(required, List.of()).size(),
                    () -> "production runtime must contain exactly one " + required + " artifact: "
                            + displayArtifacts(artifacts));
        }
        for (String forbidden : FORBIDDEN_SCHEMA_ARTIFACTS) {
            assertTrue(artifacts.getOrDefault(forbidden, List.of()).isEmpty(),
                    () -> "production runtime must not mix " + forbidden
                            + " with poi-ooxml-full: " + displayArtifacts(artifacts));
        }

        String expectedVersion = artifacts.get("poi-ooxml-full").get(0).version();
        for (Map.Entry<String, List<PoiArtifact>> entry : artifacts.entrySet()) {
            if (FORBIDDEN_SCHEMA_ARTIFACTS.contains(entry.getKey())) continue;
            for (PoiArtifact artifact : entry.getValue()) {
                assertEquals(expectedVersion, artifact.version(),
                        () -> "production POI versions must align with poi-ooxml-full: "
                                + displayArtifacts(artifacts));
            }
        }

        Map<String, String> schemaOwners = new HashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (Path path : runtime) {
            if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) continue;
            try (JarFile jar = new JarFile(path.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry jarEntry = entries.nextElement();
                    String name = jarEntry.getName();
                    if (!isOpenXmlSchemaClass(name)) continue;
                    String prior = schemaOwners.putIfAbsent(name, path.getFileName().toString());
                    if (prior != null) {
                        duplicates.add(name + " [" + prior + ", " + path.getFileName() + "]");
                    }
                }
            }
        }
        assertFalse(schemaOwners.isEmpty(), "production runtime must provide OpenXML schema classes");
        assertTrue(duplicates.isEmpty(), () -> "duplicate production OpenXML schema classes: "
                + duplicates.stream().limit(20).toList());
    }

    @Test
    void documentTypesExerciseAcceptanceCreationPathsUsingProductionRuntimeOnly() throws Exception {
        List<Path> runtime = runtimeEntries();
        URL[] urls = runtime.stream().map(PoiProductionRuntimeClasspathTest::toUrl).toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                String expectedVersion = null;
                for (DocumentProbe probe : DOCUMENT_PROBES) {
                    Class<?> type = Class.forName(probe.className(), true, loader);
                    assertEquals(loader, type.getClassLoader(), "POI type must come from production runtime");
                    String version = type.getPackage().getImplementationVersion();
                    assertNotNull(version, "POI package must expose its implementation version");
                    if (expectedVersion == null) expectedVersion = version;
                    assertEquals(expectedVersion, version, "document POI implementations must share one version");
                    Object document = type.getConstructor().newInstance();
                    AutoCloseable closeable = assertInstanceOf(AutoCloseable.class, document);
                    try {
                        // These are the exact in-memory creation paths that exposed the
                        // XmlOptions and generated-schema linkage failures in acceptance.
                        type.getMethod(probe.operation(), probe.parameterTypes()).invoke(document, probe.arguments());
                    } finally {
                        closeable.close();
                    }
                }
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    private static List<Path> runtimeEntries() {
        String classpath = System.getProperty("cyf.poi.main-runtime-classpath");
        assertNotNull(classpath, "poiProductionRuntimeClasspathTest must supply main runtimeClasspath");
        assertFalse(classpath.isBlank(), "production runtimeClasspath must not be empty");
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) entries.add(Path.of(entry));
        }
        return List.copyOf(entries);
    }

    private static Map<String, List<PoiArtifact>> poiArtifacts(List<Path> runtime) {
        Map<String, List<PoiArtifact>> artifacts = new LinkedHashMap<>();
        for (Path path : runtime) {
            Matcher matcher = POI_JAR.matcher(path.getFileName().toString());
            if (!matcher.matches()) continue;
            artifacts.computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>())
                    .add(new PoiArtifact(matcher.group(1), matcher.group(2), path.getFileName().toString()));
        }
        return artifacts;
    }

    private static boolean isOpenXmlSchemaClass(String name) {
        return name.endsWith(".class") && (name.startsWith("org/openxmlformats/schemas/")
                || name.startsWith("com/microsoft/schemas/"));
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid runtime classpath entry", exception);
        }
    }

    private static String displayArtifacts(Map<String, List<PoiArtifact>> artifacts) {
        return artifacts.values().stream().flatMap(List::stream).map(PoiArtifact::fileName).sorted().toList().toString();
    }

    private record PoiArtifact(String name, String version, String fileName) { }

    private record DocumentProbe(
            String className, String operation, Class<?>[] parameterTypes, Object[] arguments) {
        private DocumentProbe {
            parameterTypes = parameterTypes.clone();
            arguments = arguments.clone();
        }

        @Override public Class<?>[] parameterTypes() { return parameterTypes.clone(); }
        @Override public Object[] arguments() { return arguments.clone(); }
    }
}
