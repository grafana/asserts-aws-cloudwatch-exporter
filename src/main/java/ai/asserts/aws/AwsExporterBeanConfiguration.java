/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.account.AccountTenantMapper;
import ai.asserts.aws.account.HekateDistributedAccountProvider;
import ai.asserts.aws.account.NoopAccountProvider;
import ai.asserts.aws.account.SingleInstanceAccountProvider;
import ai.asserts.aws.cluster.HekateCluster;
import ai.asserts.aws.exporter.AccountIDProvider;
import ai.asserts.aws.exporter.BasicMetricCollector;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@SuppressWarnings("unused")
public class AwsExporterBeanConfiguration {
    @Bean
    public AWSApiCallRateLimiter getRateLimiter(BasicMetricCollector metricCollector,
                                                AccountTenantMapper accountTenantMapper,
                                                @Value("${aws_exporter.aws_api_calls_rate_limit:5}") double rateLimit) {
        return new AWSApiCallRateLimiter(metricCollector, accountTenantMapper, rateLimit);
    }

    @Bean
    @ConditionalOnProperty(name = "aws_exporter.tenant_mode", havingValue = "single", matchIfMissing = true)
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
    @ConditionalOnProperty(name = "aws_exporter.deployment_mode", havingValue = "single-tenant-distributed")
    public HekateCluster hekateCluster() {
        return new HekateCluster();
    }

    @Bean
    @ConditionalOnProperty(name = "aws_exporter.deployment_mode", havingValue = "single-tenant-distributed")
    public AccountProvider getDistributedAccountProvider(HekateCluster hekateCluster,
                                                         EnvironmentConfig environmentConfig,
                                                         AccountIDProvider accountIDProvider,
                                                         AssertsServerUtil assertsServerUtil,
                                                         ScrapeConfigProvider scrapeConfigProvider) {
        if (environmentConfig.isDisabled()) {
            return new NoopAccountProvider();
        } else {
            return new HekateDistributedAccountProvider(hekateCluster,
                    new SingleInstanceAccountProvider(environmentConfig, accountIDProvider, scrapeConfigProvider,
                            restTemplate(),
                            assertsServerUtil));
        }
    }

    @Bean
    @ConditionalOnProperty(name = "aws_exporter.deployment_mode", havingValue = "single-tenant-single-instance",
            matchIfMissing = true)
    public AccountProvider getSingleInstanceAccountProvider(AccountIDProvider accountIDProvider,
                                                            EnvironmentConfig environmentConfig,
                                                            AssertsServerUtil assertsServerUtil,
                                                            ScrapeConfigProvider scrapeConfigProvider) {
        if (environmentConfig.isDisabled()) {
            return new NoopAccountProvider();
        } else {
            return new SingleInstanceAccountProvider(environmentConfig, accountIDProvider, scrapeConfigProvider,
                    restTemplate(),
                    assertsServerUtil);
        }
    }
}
