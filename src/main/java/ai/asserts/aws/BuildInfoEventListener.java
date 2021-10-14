package ai.asserts.aws;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.function.Supplier;

@Slf4j
public class BuildInfoEventListener implements ApplicationListener<ApplicationReadyEvent> {
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ConfigurableApplicationContext context = event.getApplicationContext();
        BuildProperties buildProperties = context.getBean(BuildProperties.class);
        MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
        Gauge infoGauge = builder(() -> 1)
                .description("Spring boot build info")
                .tag("version", buildProperties.getVersion())
                .tag("artifact", buildProperties.getArtifact())
                .tag("name", buildProperties.getName())
                .tag("group", buildProperties.getGroup())
                .tag("time", buildProperties.getTime().toString())
                .register(meterRegistry);
        log.debug("Registered {}", infoGauge.getId());
    }

    @VisibleForTesting
    Gauge.Builder<Supplier<Number>> builder(Supplier<Number> f) {
        return Gauge.builder("springboot_build_info", f);
    }
}