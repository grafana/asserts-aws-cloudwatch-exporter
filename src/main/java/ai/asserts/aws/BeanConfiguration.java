/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@SuppressWarnings("unused")
public class BeanConfiguration {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean("metadata-trigger-thread-pool")
    public TaskThreadPool metadataTriggerPool(MeterRegistry meterRegistry) {
        return new TaskThreadPool("metadata-trigger-thread-pool", 2, meterRegistry);
    }

    @Bean("metric-task-trigger-thread-pool")
    public TaskThreadPool metricTaskTriggerPool(MeterRegistry meterRegistry) {
        return new TaskThreadPool("metric-task-trigger-thread-pool", 2, meterRegistry);
    }

    @Bean("aws-api-calls-thread-pool")
    public TaskThreadPool awsAPICallsPool(MeterRegistry meterRegistry) {
        return new TaskThreadPool("aws-api-calls-thread-pool", 5, meterRegistry);
    }
}
