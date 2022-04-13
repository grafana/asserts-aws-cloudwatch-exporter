
package ai.asserts.aws.cloudwatch.query;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.model.CWNamespace;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static java.util.concurrent.TimeUnit.MINUTES;

@Component
@Slf4j
public class MetricQueryProvider {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final QueryIdGenerator queryIdGenerator;
    private final MetricNameUtil metricNameUtil;
    private final AWSClientProvider awsClientProvider;
    private final ResourceTagHelper resourceTagHelper;
    private final MetricQueryBuilder metricQueryBuilder;
    private final Supplier<Map<String, Map<Integer, List<MetricQuery>>>> metricQueryCache;
    private final RateLimiter rateLimiter;

    public MetricQueryProvider(ScrapeConfigProvider scrapeConfigProvider,
                               QueryIdGenerator queryIdGenerator,
                               MetricNameUtil metricNameUtil,
                               AWSClientProvider awsClientProvider,
                               ResourceTagHelper resourceTagHelper,
                               MetricQueryBuilder metricQueryBuilder,
                               RateLimiter rateLimiter) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.queryIdGenerator = queryIdGenerator;
        this.metricNameUtil = metricNameUtil;
        this.awsClientProvider = awsClientProvider;
        this.resourceTagHelper = resourceTagHelper;
        this.metricQueryBuilder = metricQueryBuilder;
        this.rateLimiter = rateLimiter;
        metricQueryCache = Suppliers.memoizeWithExpiration(this::getQueriesInternal,
                scrapeConfigProvider.getScrapeConfig().getListMetricsResultCacheTTLMinutes(), MINUTES);
        log.info("Initialized..");
    }

    public Map<String, Map<Integer, List<MetricQuery>>> getMetricQueries() {
        return metricQueryCache.get();
    }

    Map<String, Map<Integer, List<MetricQuery>>> getQueriesInternal() {
        log.info("Will discover metrics and build metric queries");
        Map<String, Map<Integer, List<MetricQuery>>> queriesByInterval = new TreeMap<>();

        ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
        scrapeConfig.getRegions().forEach(region -> scrapeConfig.getNamespaces().forEach(ns -> {
            try (CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(region)) {
                Set<Resource> tagFilteredResources = resourceTagHelper.getFilteredResources(region, ns);
                if (!ns.hasTagFilters() || tagFilteredResources.size() > 0) {

                    Map<String, MetricConfig> configuredMetrics = new TreeMap<>();
                    ns.getMetrics().forEach(metricConfig -> configuredMetrics.put(metricConfig.getName(),
                            metricConfig));

                    String nextToken = null;
                    do {
                        ListMetricsRequest.Builder builder = ListMetricsRequest.builder()
                                .nextToken(nextToken);
                        Optional<CWNamespace> nsOpt = scrapeConfigProvider.getStandardNamespace(ns.getName());
                        if (nsOpt.isPresent()) {
                            String namespace = nsOpt.get().getNamespace();
                            builder = builder.namespace(namespace);
                            log.info("Discovering all metrics for region={}, namespace={} ", region, namespace);
                        } else {
                            builder = builder.namespace(ns.getName());
                            log.info("Discovering all metrics for region={}, namespace={} ", region, ns.getName());
                        }

                        ListMetricsRequest request = builder.build();
                        ListMetricsResponse response = rateLimiter.doWithRateLimit(
                                "CloudWatchClient/listMetrics",
                                operationLabels(region, ns),
                                () -> cloudWatchClient.listMetrics(request));

                        if (response.hasMetrics()) {
                            // Check if the metric is on a tag filtered resource
                            // Also check if the metric matches any dimension filters that might be specified
                            response.metrics()
                                    .stream()
                                    .filter(metric -> isAConfiguredMetric(configuredMetrics, metric) &&
                                            belongsToFilteredResource(ns, tagFilteredResources, metric))
                                    .forEach(metric -> buildQueries(queriesByInterval, region,
                                            tagFilteredResources,
                                            configuredMetrics.get(metric.metricName()),
                                            metric));
                        }
                        nextToken = response.nextToken();
                    } while (nextToken != null);
                }
            } catch (Exception e) {
                log.info("Failed to scrape metrics", e);
            }
        }));

        Set<String> metricNames = new HashSet<>();
        queriesByInterval.forEach((region, byInterval) -> byInterval.forEach((interval, metrics) ->
                queriesByInterval.get(region).get(interval).forEach(metricQuery -> {
                    String exportedMetricName = metricNameUtil.exportedMetricName(metricQuery.getMetric(),
                            metricQuery.getMetricStat());
                    if (metricNames.add(exportedMetricName)) {
                        log.info("Will scrape {} agg over {} seconds every {} seconds",
                                exportedMetricName,
                                metricQuery.getMetricConfig().getScrapeInterval(),
                                interval);
                    }
                })));


        return queriesByInterval;
    }

    private ImmutableSortedMap<String, String> operationLabels(String region, NamespaceConfig ns) {
        return ImmutableSortedMap.of(
                SCRAPE_REGION_LABEL, region,
                SCRAPE_OPERATION_LABEL, "list_metrics",
                SCRAPE_NAMESPACE_LABEL, ns.getName()
        );
    }

    private boolean belongsToFilteredResource(NamespaceConfig namespaceConfig, Set<Resource> tagFilteredResources,
                                              Metric metric) {
        return !namespaceConfig.hasTagFilters() ||
                tagFilteredResources.stream().anyMatch(resource -> resource.matches(metric));
    }

    private boolean isAConfiguredMetric(Map<String, MetricConfig> byName, Metric metric) {
        return byName.containsKey(metric.metricName()) && byName.get(metric.metricName()).matchesMetric(metric);
    }

    private void buildQueries(Map<String, Map<Integer, List<MetricQuery>>> byIntervalWithDimensions, String region,
                              Set<Resource> resources, MetricConfig metricConfig, Metric metric) {
        List<MetricQuery> metricQueries = byIntervalWithDimensions
                .computeIfAbsent(region, k -> new TreeMap<>())
                .computeIfAbsent(metricConfig.getScrapeInterval(), k -> new ArrayList<>());
        metricQueries.addAll(metricQueryBuilder.buildQueries(queryIdGenerator, resources, metricConfig,
                metric));
    }
}
