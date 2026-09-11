package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.output-delivery")
public record OutputDeliveryProperties(
        boolean enabled,
        String storageEndpoint,
        String storageAccessKey,
        String storageSecretKey,
        String storageBucket,
        String scannerHost,
        Integer scannerPort,
        Integer retryMultiplier,
        Long archiveMemberMaxBytes,
        Long archiveTreeMaxBytes,
        String archiveTempDirectory) {
    public OutputDeliveryProperties(boolean enabled) {
        this(enabled, null, null, null, "cyf-agent-outputs", "127.0.0.1", 3310, 10,
                50L * 1024 * 1024, 90L * 1024 * 1024, null);
    }

    public OutputDeliveryProperties(boolean enabled, String storageEndpoint, String storageAccessKey,
            String storageSecretKey, String storageBucket, String scannerHost, Integer scannerPort,
            Integer retryMultiplier) {
        this(enabled, storageEndpoint, storageAccessKey, storageSecretKey, storageBucket, scannerHost,
                scannerPort, retryMultiplier, 50L * 1024 * 1024, 90L * 1024 * 1024, null);
    }

    public OutputDeliveryProperties {
        storageBucket = storageBucket == null ? "cyf-agent-outputs" : storageBucket;
        scannerHost = scannerHost == null ? "127.0.0.1" : scannerHost;
        scannerPort = scannerPort == null ? 3310 : scannerPort;
        retryMultiplier = retryMultiplier == null ? 10 : retryMultiplier;
        archiveMemberMaxBytes = archiveMemberMaxBytes == null ? 50L * 1024 * 1024 : archiveMemberMaxBytes;
        archiveTreeMaxBytes = archiveTreeMaxBytes == null ? 90L * 1024 * 1024 : archiveTreeMaxBytes;
        archiveTempDirectory = archiveTempDirectory == null || archiveTempDirectory.isBlank() ? null : archiveTempDirectory;
        if (scannerPort < 1 || scannerPort > 65535 || retryMultiplier < 1 || retryMultiplier > 100)
            throw new IllegalArgumentException("invalid output delivery infrastructure limits");
        if (archiveMemberMaxBytes < 1 || archiveMemberMaxBytes > 50L * 1024 * 1024
                || archiveTreeMaxBytes < archiveMemberMaxBytes || archiveTreeMaxBytes > 90L * 1024 * 1024)
            throw new IllegalArgumentException("invalid output archive limits");
    }
}
