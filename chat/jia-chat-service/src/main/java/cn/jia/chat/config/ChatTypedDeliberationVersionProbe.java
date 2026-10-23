package cn.jia.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Reads the existing deployment marker; never migrates or inspects the schema catalogue. */
@Component
@Profile("!schema-maintenance")
public final class ChatTypedDeliberationVersionProbe
        implements ChatTypedDeliberationReadiness, ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private volatile Readiness readiness;

    public ChatTypedDeliberationVersionProbe(JdbcTemplate jdbc,
            @Value("${chat.typed-deliberation.enabled:false}") boolean enabled) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.enabled = enabled;
        this.readiness = enabled ? Readiness.INITIALIZING : Readiness.DISABLED;
    }

    @Override
    public Readiness readiness() {
        return readiness;
    }

    @Override
    public synchronized void run(ApplicationArguments args) {
        if (!enabled) return;
        readiness = Readiness.INITIALIZING;
        try {
            List<String> stages = jdbc.queryForList("""
                    SELECT stage FROM chat_deliberation_schema_version
                    WHERE version=3 LIMIT 2
                    """, String.class);
            if (stages.isEmpty()) {
                readiness = Readiness.UNAVAILABLE;
                return;
            }
            if (stages.size() != 1 || !"APPLIED".equals(stages.getFirst())) {
                throw new IllegalStateException("Typed deliberation schema version 3 is not APPLIED; run deployment maintenance");
            }
            readiness = Readiness.READY;
        } catch (RuntimeException | Error failure) {
            readiness = Readiness.FAILED;
            throw failure;
        }
    }
}
