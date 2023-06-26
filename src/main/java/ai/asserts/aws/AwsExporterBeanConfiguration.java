/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.account.HekateDistributedAccountProvider;
import ai.asserts.aws.account.SingleInstanceAccountProvider;
import ai.asserts.aws.cluster.HekateCluster;
import ai.asserts.aws.exporter.AccountIDProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@SuppressWarnings("unused")
public class AwsExporterBeanConfiguration {
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

    @Bean
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "single-tenant-distributed")
    public AccountProvider getDistributedAccountProvider(HekateCluster hekateCluster,
                                                         AccountIDProvider accountIDProvider,
                                                         AssertsServerUtil assertsServerUtil,
                                                         ScrapeConfigProvider scrapeConfigProvider) {
        return new HekateDistributedAccountProvider(hekateCluster,
                new SingleInstanceAccountProvider(accountIDProvider, scrapeConfigProvider, restTemplate(),
                        assertsServerUtil));
    }

    @Bean
    @ConditionalOnProperty(name = "deployment.mode", havingValue = "single-tenant-single-instance", matchIfMissing =
            true)
    public AccountProvider getSingleInstanceAccountProvider(AccountIDProvider accountIDProvider,
                                                            AssertsServerUtil assertsServerUtil,
                                                            ScrapeConfigProvider scrapeConfigProvider) {
        return new SingleInstanceAccountProvider(accountIDProvider, scrapeConfigProvider, restTemplate(),
                assertsServerUtil);
    }
}
