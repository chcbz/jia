package cn.jia.core.diagnostics;

import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;

import java.util.function.Supplier;

/** Explicit application scopes preserve the original return values and exceptions. */
public final class StartupTiming {
    private static volatile ApplicationStartup startup = ApplicationStartup.DEFAULT;

    private StartupTiming() { }

    public static boolean requested(String[] args) {
        boolean enabled = false;
        for (String arg : args) {
            if (arg.startsWith("--cyf.startup-tracing.enabled=")) {
                enabled = "--cyf.startup-tracing.enabled=true".equals(arg);
            }
        }
        return enabled;
    }

    public static void use(ApplicationStartup value) {
        startup = value == null ? ApplicationStartup.DEFAULT : value;
    }

    public static void run(String name, Runnable action) {
        call(name, () -> { action.run(); return null; });
    }

    public static <T> T call(String name, Supplier<T> action) {
        ApplicationStartup current = startup;
        if (current == ApplicationStartup.DEFAULT) return action.get();
        StartupStep step = current.start(name);
        boolean successful = false;
        try {
            T result = action.get();
            successful = true;
            return result;
        } finally {
            step.tag("outcome", successful ? "success" : "failed").end();
        }
    }
}
