package cn.jia.chat.archive.content;

import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.service.ArchiveContentImporter;
import cn.jia.chat.archive.service.SpringArchiveTransactions;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;

/** Operator-only standalone entry point. Does not create a Spring application or web server. */
public final class ArchiveContentCommand {
    private ArchiveContentCommand() { }

    public static void main(String[] args) {
        try {
            execute(args);
        } catch (Exception failure) {
            // JDBC exception messages can contain URLs/credentials. Do not echo them.
            System.err.println("ARCHIVE_CONTENT_COMMAND_FAILED; no successful publication is claimed");
            System.exit(1);
        }
    }

    static void execute(String[] args) throws Exception {
        if (args.length < 3 || !("validate".equals(args[0]) && args.length == 3
                || "publish".equals(args[0]) && args.length == 4)) {
            throw new IllegalArgumentException("Expected validate MANIFEST SHA256 or publish MANIFEST SHA256 PRIVATE_DB_PROPERTIES");
        }
        ArchiveManifestLoader.Loaded loaded = new ArchiveManifestLoader().parseAndValidate(
                Files.readAllBytes(Path.of(args[1])), args[2]);
        if ("validate".equals(args[0])) {
            System.out.println("ARCHIVE_PACKAGE_VALIDATED sha256=" + loaded.manifestFileSha256());
            return;
        }
        Path configuration = Path.of(args[3]);
        if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Private database configuration must be a regular file");
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(configuration, LinkOption.NOFOLLOW_LINKS);
        if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) {
            throw new IllegalArgumentException("Database configuration must be owner-only");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configuration)) { properties.load(input); }
        String url = properties.getProperty("url");
        String user = properties.getProperty("user");
        String password = properties.getProperty("password");
        if (url == null || !url.startsWith("jdbc:mysql:") || user == null || user.isBlank() || password == null) {
            throw new IllegalArgumentException("Explicit MySQL credentials are required");
        }
        DriverManagerDataSource source = new DriverManagerDataSource(url, user, password);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        // Detect incompatible/partial schemas before inserting any content. No migration here.
        new ArchiveSchemaInitializer(jdbc).initialize();
        new ArchiveContentImporter(new JdbcArchiveContentStore(jdbc),
                new SpringArchiveTransactions(new DataSourceTransactionManager(source)))
                .importAndActivate(loaded.manifest(), loaded.manifestFileSha256());
        System.out.println("ARCHIVE_CONTENT_PUBLISHED work=" + loaded.manifest().workId()
                + " edition=" + loaded.manifest().editionId() + " sha256=" + loaded.manifestFileSha256());
    }
}
