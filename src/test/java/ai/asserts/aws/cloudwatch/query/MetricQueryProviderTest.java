
package ai.asserts.aws.cloudwatch.query;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.TestTaskThreadPool;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.BasicMetricCollector;
import ai.asserts.aws.model.CWNamespace;
import ai.asserts.aws.model.MetricStat;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.Optional;

import static ai.asserts.aws.model.CWNamespace.lambda;
import static ai.asserts.aws.model.MetricStat.Average;
import static ai.asserts.aws.model.MetricStat.Sum;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;

public class MetricQueryProviderTest extends EasyMockSupport {
    private AWSAccount accountRegion;
    private AccountProvider accountProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private QueryIdGenerator queryIdGenerator;
    private MetricNameUtil metricNameUtil;
    private AWSClientProvider awsClientProvider;
    private CloudWatchClient cloudWatchClient;
    private ResourceTagHelper resourceTagHelper;
    private MetricQueryBuilder metricQueryBuilder;
    private Resource resource;
    private Metric metric;
    private NamespaceConfig namespaceConfig;
    private MetricConfig metricConfig;
    private MetricQuery metricQuery;
    private BasicMetricCollector metricCollector;
    private MetricQueryProvider testClass;
    private final CWNamespace _CW_namespace = lambda;
    private final String metricName = "Invocations";

    @BeforeEach
    public void setup() {
        accountProvider = mock(AccountProvider.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        queryIdGenerator = mock(QueryIdGenerator.class);
        metricNameUtil = mock(MetricNameUtil.class);
        awsClientProvider = mock(AWSClientProvider.class);
        cloudWatchClient = mock(CloudWatchClient.class);
        resourceTagHelper = mock(ResourceTagHelper.class);
        metricQueryBuilder = mock(MetricQueryBuilder.class);
        metricQuery = mock(MetricQuery.class);
        resource = mock(Resource.class);
        metricConfig = mock(MetricConfig.class);
        namespaceConfig = mock(NamespaceConfig.class);
        metricQuery = mock(MetricQuery.class);
        metricCollector = mock(BasicMetricCollector.class);
        TaskExecutorUtil taskExecutorUtil = new TaskExecutorUtil(new TestTaskThreadPool(), new RateLimiter(metricCollector));

        metric = Metric.builder()
                .namespace(lambda.getNamespace())
                .metricName(metricName)
                .build();

        accountRegion = new AWSAccount("tenant", "account", "", "", "role",
                ImmutableSet.of("region1"));

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(ScrapeConfig.builder().build());
        replayAll();
        testClass = new MetricQueryProvider(accountProvider, scrapeConfigProvider, queryIdGenerator, metricNameUtil,
                awsClientProvider, resourceTagHelper, metricQueryBuilder,
                new RateLimiter(metricCollector), taskExecutorUtil);
        verifyAll();
        resetAll();
    }

    @Test
    void getMetricQueries_CWMetricPullEnabled() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion)).anyTimes();
        expect(namespaceConfig.isEnabled()).andReturn(true).anyTimes();
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfigProvider.getStandardNamespace(_CW_namespace.name()))
                .andReturn(Optional.of(lambda)).anyTimes();
        expect(awsClientProvider.getCloudWatchClient("region1", accountRegion)).andReturn(cloudWatchClient);

        expect(namespaceConfig.hasTagFilters()).andReturn(true).anyTimes();

        expect(resourceTagHelper.getFilteredResources(accountRegion, "region1", namespaceConfig))
                .andReturn(ImmutableSet.of(resource));
        expect(resource.matches(metric)).andReturn(true).anyTimes();

        expect(namespaceConfig.getName()).andReturn(_CW_namespace.name()).anyTimes();
        expect(namespaceConfig.getMetrics()).andReturn(ImmutableList.of(metricConfig));

        expect(metricConfig.getName()).andReturn(metricName).anyTimes();
        expect(metricConfig.getEffectiveScrapeInterval()).andReturn(60).anyTimes();
        expect(metricConfig.matchesMetric(metric)).andReturn(true).anyTimes();

        ListMetricsResponse listMetricsResponse1 = ListMetricsResponse.builder()
                .metrics(ImmutableList.of(metric))
                .nextToken("token-1")
                .build();
        expect(cloudWatchClient.listMetrics(ListMetricsRequest.builder()
                .namespace(_CW_namespace.getNamespace())
                .build())).andReturn(listMetricsResponse1);
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());

        expect(metricQuery.getMetric()).andReturn(metric).anyTimes();
        expect(metricQuery.getMetricConfig()).andReturn(metricConfig).anyTimes();
        expect(metricQuery.getMetricStat()).andReturn(Sum);
        expectMetricQuery(Sum, "metric_sum");

        ListMetricsResponse listMetricsResponse2 = ListMetricsResponse.builder()
                .metrics(ImmutableList.of(metric))
                .nextToken(null)
                .build();
        expect(cloudWatchClient.listMetrics(ListMetricsRequest.builder()
                .nextToken("token-1")
                .namespace(_CW_namespace.getNamespace())
                .build())).andReturn(listMetricsResponse2);
        metricCollector.recordLatency(anyObject(), anyObject(), anyLong());

        expect(metricQuery.getMetricStat()).andReturn(Average);
        expectMetricQuery(Average, "metric_avg");
        replayAll();
        testClass.getMetricQueries();
        verifyAll();
    }

    @Test
    void getMetricQueries_Exception() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion)).anyTimes();
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .build();
        expect(namespaceConfig.isEnabled()).andReturn(true).anyTimes();
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(awsClientProvider.getCloudWatchClient("region1", accountRegion)).andReturn(cloudWatchClient);

        expect(namespaceConfig.hasTagFilters()).andReturn(true).anyTimes();

        expect(resourceTagHelper.getFilteredResources(accountRegion, "region1", namespaceConfig))
                .andThrow(new RuntimeException());

        replayAll();
        testClass.getMetricQueries();
        verifyAll();
    }

    @Test
    void getMetricQueries_CWMetricPullDisabled() {
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(accountRegion)).anyTimes();
        expect(namespaceConfig.isEnabled()).andReturn(true).anyTimes();
        ScrapeConfig scrapeConfig = ScrapeConfig.builder()
                .regions(ImmutableSet.of("region1"))
                .namespaces(ImmutableList.of(namespaceConfig))
                .fetchCWMetrics(false)
                .build();

        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        replayAll();
        testClass.getMetricQueries();
        verifyAll();
    }

    private void expectMetricQuery(MetricStat stat, String metricName) {
        expect(metricQueryBuilder.buildQueries(queryIdGenerator, ImmutableSet.of(resource), metricConfig, metric))
                .andReturn(ImmutableList.of(metricQuery));

        expect(metricNameUtil.exportedMetricName(metric, stat)).andReturn(metricName);
    }
}
