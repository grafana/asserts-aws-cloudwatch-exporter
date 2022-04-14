
package ai.asserts.aws.cloudwatch.query;

import ai.asserts.aws.config.MetricConfig;
import ai.asserts.aws.model.MetricStat;
import ai.asserts.aws.resource.Resource;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;

@Getter
@Setter
@EqualsAndHashCode
@Builder
@ToString
public class MetricQuery {
    private final MetricConfig metricConfig;
    private final Metric metric;
    private final MetricStat metricStat;
    private final MetricDataQuery metricDataQuery;
    @Setter
    private Resource resource;
}
