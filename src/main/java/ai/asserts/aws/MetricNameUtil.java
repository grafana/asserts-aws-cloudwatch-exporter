
package ai.asserts.aws;

import ai.asserts.aws.model.CWNamespace;
import ai.asserts.aws.model.MetricStat;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.Optional;

import static java.lang.String.format;

@Component
@AllArgsConstructor
public class MetricNameUtil {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final SnakeCaseUtil snakeCaseUtil;
    public static final String SCRAPE_LATENCY_METRIC = "aws_exporter_milliseconds";
    public static final String ASSERTS_ERROR_TYPE = "asserts_error_type";
    public static final String TENANT = "tenant";
    public static final String ASSERTS_CUSTOMER = "asserts_customer";
    public static final String ENV = "asserts_env";
    public static final String SITE = "asserts_site";
    public static final String SCRAPE_ERROR_COUNT_METRIC = "aws_exporter_error_total";
    public static final String SCRAPE_OPERATION_LABEL = "operation";
    public static final String SCRAPE_REGION_LABEL = "region";
    public static final String SCRAPE_ACCOUNT_ID_LABEL = "account_id";
    public static final String SCRAPE_NAMESPACE_LABEL = "cw_namespace";
    public static final String SCRAPE_INTERVAL_LABEL = "interval";
    public static final String EXPORTER_DELAY_SECONDS = "aws_exporter_delay_seconds";

    public String exportedMetricName(Metric metric, MetricStat metricStat) {
        String namespace = metric.namespace();
        String metricPrefix = getMetricPrefix(namespace);
        return format("%s_%s_%s", metricPrefix, toSnakeCase(metric.metricName()),
                metricStat.getShortForm().toLowerCase());

    }

    public String getMetricPrefix(String namespace) {
        Optional<CWNamespace> nsOpt = scrapeConfigProvider.getStandardNamespace(namespace);
        if (nsOpt.isPresent()) {
            return nsOpt.get().getMetricPrefix();
        } else {
            return toSnakeCase(namespace);
        }
    }

    public String getLambdaMetric(String suffix) {
        return format("%s_%s", getMetricPrefix(CWNamespace.lambda.getNamespace()), suffix);
    }

    public String toSnakeCase(String input) {
        return snakeCaseUtil.toSnakeCase(input);
    }
}
