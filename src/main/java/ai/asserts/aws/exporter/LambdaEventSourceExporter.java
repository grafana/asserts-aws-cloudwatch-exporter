
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.AWSApiCallRateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.SimpleTenantTask;
import ai.asserts.aws.TaskExecutorUtil;
import ai.asserts.aws.account.AWSAccount;
import ai.asserts.aws.account.AccountProvider;
import ai.asserts.aws.config.NamespaceConfig;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.EventSourceMappingConfiguration;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsRequest;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_NAMESPACE_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static java.lang.String.format;

@Component
@Slf4j
public class LambdaEventSourceExporter extends Collector implements MetricProvider {
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final MetricNameUtil metricNameUtil;
    private final ResourceMapper resourceMapper;
    private final ResourceTagHelper resourceTagHelper;
    private final MetricSampleBuilder sampleBuilder;
    private final AWSApiCallRateLimiter rateLimiter;
    private final TaskExecutorUtil taskExecutorUtil;
    private volatile List<MetricFamilySamples> metrics;

    public LambdaEventSourceExporter(AccountProvider accountProvider,
                                     ScrapeConfigProvider scrapeConfigProvider, AWSClientProvider awsClientProvider,
                                     MetricNameUtil metricNameUtil,
                                     ResourceMapper resourceMapper,
                                     ResourceTagHelper resourceTagHelper,
                                     MetricSampleBuilder sampleBuilder,
                                     AWSApiCallRateLimiter rateLimiter, TaskExecutorUtil taskExecutorUtil) {
        this.accountProvider = accountProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.metricNameUtil = metricNameUtil;
        this.resourceMapper = resourceMapper;
        this.resourceTagHelper = resourceTagHelper;
        this.sampleBuilder = sampleBuilder;
        this.rateLimiter = rateLimiter;
        this.taskExecutorUtil = taskExecutorUtil;
        this.metrics = new ArrayList<>();
    }

    public List<MetricFamilySamples> collect() {
        return metrics;
    }

    @Override
    public void update() {
        log.info("Updating Lambda Event Sources");
        try {
            this.metrics = getMappings();
        } catch (Exception e) {
            log.error("Failed to discover Lambda event source mappings", e);
        }
    }

    private List<MetricFamilySamples> getMappings() {
        Map<String, List<Sample>> samplesByName = new TreeMap<>();
        List<Future<Map<String, List<Sample>>>> futures = new ArrayList<>();
        for (AWSAccount accountRegion : accountProvider.getAccounts()) {
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig(accountRegion.getTenant());
            Optional<NamespaceConfig> lambdaConfig = scrapeConfig.getLambdaConfig();
            lambdaConfig.ifPresent(namespaceConfig -> accountRegion.getRegions().forEach(region ->
                    futures.add(taskExecutorUtil.executeAccountTask(accountRegion,
                            new SimpleTenantTask<Map<String, List<Sample>>>() {
                                @Override
                                public Map<String, List<Sample>> call() {
                                    return buildSamples(region, accountRegion, namespaceConfig, samplesByName);
                                }
                            }))));
        }
        taskExecutorUtil.awaitAll(futures, (byName) -> byName.forEach((name, samples) ->
                samplesByName.computeIfAbsent(name, k -> new ArrayList<>()).addAll(samples)));
        return samplesByName.values().stream()
                .map(sampleBuilder::buildFamily)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    private Map<String, List<Sample>> buildSamples(String region, AWSAccount accountRegion,
                                                   NamespaceConfig namespaceConfig,
                                                   Map<String, List<Sample>> samplesByName) {
        Map<String, List<Sample>> samples = new TreeMap<>();
        Map<String, List<EventSourceMappingConfiguration>> byRegion = new TreeMap<>();
        try {
            LambdaClient client = awsClientProvider.getLambdaClient(region, accountRegion);
            // Get all event source mappings
            log.info("Discovering Lambda event source mappings for region={}", region);
            String nextToken = null;
            do {
                ListEventSourceMappingsRequest req = ListEventSourceMappingsRequest.builder()
                        .marker(nextToken)
                        .build();
                String listEventSourceMappings = "LambdaClient/listEventSourceMappings";
                ListEventSourceMappingsResponse response = rateLimiter.doWithRateLimit(
                        listEventSourceMappings,
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, listEventSourceMappings,
                                SCRAPE_NAMESPACE_LABEL, "AWS/Lambda"
                        ),
                        () -> client.listEventSourceMappings(req));
                if (response.hasEventSourceMappings()) {
                    byRegion.computeIfAbsent(region, k -> new ArrayList<>())
                            .addAll(response.eventSourceMappings().stream()
                                    .filter(mapping -> resourceMapper.map(mapping.functionArn())
                                            .isPresent())
                                    .collect(Collectors.toList()));

                }
                nextToken = response.nextMarker();
            } while (StringUtils.hasText(nextToken));

            Set<Resource> fnResources =
                    resourceTagHelper.getFilteredResources(accountRegion, region, namespaceConfig);
            byRegion.computeIfAbsent(region, k -> new ArrayList<>())
                    .forEach(mappingConfiguration -> {
                        Optional<Resource> fnResource = Optional.ofNullable(fnResources.stream()
                                .filter(r -> r.getArn().equals(mappingConfiguration.functionArn()))
                                .findFirst()
                                .orElse(resourceMapper.map(mappingConfiguration.functionArn())
                                        .orElse(null)));
                        Optional<Resource> eventResourceOpt =
                                resourceMapper.map(mappingConfiguration.eventSourceArn());
                        eventResourceOpt.ifPresent(eventResource ->
                                fnResource.ifPresent(
                                        fn -> buildSample(region, fn, eventResource, samplesByName))
                        );
                    });
        } catch (Exception e) {
            log.info("Failed to discover event source mappings", e);
        }
        return samples;
    }

    private void buildSample(String region, Resource functionResource, Resource eventSourceResource,
                             Map<String, List<Sample>> samples) {
        String metricPrefix = metricNameUtil.getMetricPrefix(lambda.getNamespace());
        String metricName = format("%s_event_source", metricPrefix);
        Map<String, String> labels = new TreeMap<>();
        labels.put("region", region);
        labels.put("lambda_function", functionResource.getName());
        labels.put(SCRAPE_ACCOUNT_ID_LABEL, functionResource.getAccount());
        eventSourceResource.addLabels(labels, "event_source");
        functionResource.addEnvLabel(labels, metricNameUtil);
        sampleBuilder.buildSingleSample(metricName, labels, 1.0D)
                .ifPresent(sample -> samples.computeIfAbsent(metricName, k -> new ArrayList<>()).add(sample));
    }
}
