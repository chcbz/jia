package cn.jia.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Real application-runtime linkage, isolated from all test-only libraries; no SMTP connection. */
class SmsEmailRuntimeClasspathTest {
    private static final String EMAIL_CLIENT = "cn.jia.sms.config.SmsExternalEmailClient";
    private static final String MAIL_EXCEPTION = "javax.mail.MessagingException";

    @Test
    void mainRuntimeIntrospectsEmailComponentsAndCreatesDisconnectedSmtpTransport() throws Exception {
        try (URLClassLoader loader = runtimeLoader(false)) {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                // Equivalent linkage pressure to component introspection, not a mock SmsClient.
                introspect(loader, EMAIL_CLIENT);
                // Retain the legacy utility's javax.mail/activation binary contract without
                // constructing it (its constructor changes global mail properties).
                introspect(loader, "cn.jia.core.util.EmailUtil");
                assertSame(loader, loader.loadClass(MAIL_EXCEPTION).getClassLoader());
                loader.loadClass("javax.activation.DataHandler");
                loader.loadClass("javax.activation.FileDataSource");

                Class<?> sessionType = loader.loadClass("javax.mail.Session");
                Properties properties = new Properties();
                Object session = sessionType.getMethod("getInstance", Properties.class)
                        .invoke(null, properties);
                Class<?> mimeType = loader.loadClass("javax.mail.internet.MimeMessage");
                Object message = mimeType.getConstructor(sessionType).newInstance(session);
                mimeType.getMethod("setText", String.class).invoke(message, "offline fixture");
                assertEquals("offline fixture", mimeType.getMethod("getContent").invoke(message));

                // Provider discovery/construction only: never connect(), send() or credentials.
                Object transport = sessionType.getMethod("getTransport", String.class)
                        .invoke(session, "smtp");
                assertEquals("com.sun.mail.smtp.SMTPTransport", transport.getClass().getName());
                assertSame(loader, transport.getClass().getClassLoader());
                assertEquals(Boolean.FALSE, transport.getClass().getMethod("isConnected")
                        .invoke(transport));
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    @Test
    void removingMailFromMainRuntimeCannotBeMaskedByTestClasspath() throws Exception {
        try (URLClassLoader loader = runtimeLoader(true)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(MAIL_EXCEPTION));
            assertThrows(NoClassDefFoundError.class, () -> introspect(loader, EMAIL_CLIENT));
        }
    }

    private static void introspect(ClassLoader loader, String name) throws Exception {
        Class<?> type = Class.forName(name, false, loader);
        type.getDeclaredConstructors();
        type.getDeclaredMethods();
        type.getDeclaredFields();
    }

    private static URLClassLoader runtimeLoader(boolean removeMail) throws Exception {
        String classpath = System.getProperty("cyf.sms.main-runtime-classpath");
        assertNotNull(classpath, "starter:test must supply sourceSets.main.runtimeClasspath");
        assertFalse(classpath.isBlank(), "application runtime classpath must not be empty");
        List<URL> urls = new ArrayList<>();
        int removed = 0;
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(entry);
            if (removeMail && containsMailApi(path)) {
                removed++;
            } else {
                urls.add(path.toUri().toURL());
            }
        }
        if (removeMail) {
            assertTrue(removed > 0, "mutation must remove the actual production mail artifact");
        }
        // Platform-only parent prevents JUnit/testImplementation from supplying missing mail.
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static boolean containsMailApi(Path path) throws Exception {
        String entry = "javax/mail/MessagingException.class";
        if (Files.isDirectory(path)) {
            return Files.exists(path.resolve(entry));
        }
        if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
            try (JarFile jar = new JarFile(path.toFile())) {
                return jar.getJarEntry(entry) != null;
            }
        }
        return false;
    }
}
