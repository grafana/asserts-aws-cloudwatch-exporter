
package ai.asserts.aws;

import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.cloudwatch.model.MetricStat;
import ai.asserts.aws.cloudwatch.query.MetricQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

@Component
@AllArgsConstructor
public class MetricNameUtil {
    private final ScrapeConfigProvider scrapeConfigProvider;
    public static final String SCRAPE_LATENCY_METRIC = "aws_exporter_milliseconds";
    public static final String STREAM_LATENCY_METRIC = "aws_metric_delivery_latency_milliseconds";
    public static final String SCRAPE_ERROR_COUNT_METRIC = "aws_exporter_error_total";
    public static final String SCRAPE_OPERATION_LABEL = "operation";
    public static final String SCRAPE_REGION_LABEL = "region";
    public static final String SCRAPE_ACCOUNT_ID_LABEL = "account_id";
    public static final String SCRAPE_NAMESPACE_LABEL = "cw_namespace";
    public static final String SCRAPE_INTERVAL_LABEL = "interval";

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
        StringBuilder builder = new StringBuilder();
        boolean lastCaseWasSmall = false;
        int numContiguousUpperCase = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '-' || c == ':' || c == '/' || c == '.') {
                builder.append("_");
                numContiguousUpperCase = 0;
                continue;
            } else if (Character.isUpperCase(c) && lastCaseWasSmall) {
                builder.append("_");
            } else if (Character.isLowerCase(c) && numContiguousUpperCase > 1) {
                char lastUpperCaseLetter = builder.toString().charAt(builder.length() - 1);
                builder.deleteCharAt(builder.length() - 1);
                builder.append("_");
                builder.append(lastUpperCaseLetter);
            }
            builder.append(c);
            lastCaseWasSmall = Character.isLowerCase(c);
            if (Character.isUpperCase(c)) {
                numContiguousUpperCase++;
            } else {
                numContiguousUpperCase = 0;
            }
        }
        return builder.toString().toLowerCase();
    }
}
