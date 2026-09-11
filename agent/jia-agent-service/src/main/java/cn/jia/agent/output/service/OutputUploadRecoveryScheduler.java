package cn.jia.agent.output.service;

import cn.jia.agent.output.OutputUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** Polls only durable SQL leases; no recovery work exists solely in process memory. */
public final class OutputUploadRecoveryScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(OutputUploadRecoveryScheduler.class);
    private final OutputUploadService service;
    public OutputUploadRecoveryScheduler(OutputUploadService service){this.service=Objects.requireNonNull(service);}
    @Scheduled(fixedDelayString="${agent.output-delivery.recovery-delay-millis:1000}")
    public void recover(){try{service.recover(25);}catch(RuntimeException failure){LOG.warn("Output delivery recovery pass failed: {}",failure.getClass().getSimpleName());}}
}
