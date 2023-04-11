
package ai.asserts.aws;

import ai.asserts.aws.model.MetricStat;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.util.Optional;

import static ai.asserts.aws.model.CWNamespace.dynamodb;
import static ai.asserts.aws.model.CWNamespace.ecs_containerinsights;
import static ai.asserts.aws.model.CWNamespace.ecs_svc;
import static ai.asserts.aws.model.CWNamespace.lambda;
import static ai.asserts.aws.model.CWNamespace.lambdainsights;
import static ai.asserts.aws.model.CWNamespace.s3;
import static ai.asserts.aws.model.CWNamespace.sqs;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetricNameUtilTest extends EasyMockSupport {
    private ScrapeConfigProvider scrapeConfigProvider;
    private MetricNameUtil util;

    @BeforeEach
    public void setup() {
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        util = new MetricNameUtil(scrapeConfigProvider, new SnakeCaseUtil());
    }

    @Test
    void toSnakeCase() {
        assertEquals("method_duration_seconds", util.toSnakeCase("MethodDurationSeconds"));
        assertEquals("cpu_load15", util.toSnakeCase("CPULoad15"));
        assertEquals("cpu_load", util.toSnakeCase("cpu-load"));
        assertEquals("kubernetes_io_service_name", util.toSnakeCase("kubernetes.io/service_name"));
        assertEquals("tag_lambda_console_blueprint", util.toSnakeCase("tag_lambda_console:blueprint"));
        assertEquals("put_records_throttled_records", util.toSnakeCase("PutRecords.ThrottledRecords"));
    }

    @Test
    void getMetricPrefix() {
        expect(scrapeConfigProvider.getStandardNamespace("AWS/Lambda")).andReturn(Optional.of(lambda));
        expect(scrapeConfigProvider.getStandardNamespace("AWS/ECS")).andReturn(Optional.of(ecs_svc));
        expect(scrapeConfigProvider.getStandardNamespace("ECS/ContainerInsights"))
                .andReturn(Optional.of(ecs_containerinsights));
        expect(scrapeConfigProvider.getStandardNamespace("LambdaInsights")).andReturn(Optional.of(lambdainsights));
        expect(scrapeConfigProvider.getStandardNamespace("AWS/SQS")).andReturn(Optional.of(sqs));
        expect(scrapeConfigProvider.getStandardNamespace("AWS/S3")).andReturn(Optional.of(s3));
        expect(scrapeConfigProvider.getStandardNamespace("AWS/DynamoDB")).andReturn(Optional.of(dynamodb));
        expect(scrapeConfigProvider.getStandardNamespace("AWS/Unknown")).andReturn(Optional.empty());
        replayAll();
        assertEquals("aws_lambda", util.getMetricPrefix("AWS/Lambda"));
        assertEquals("aws_ecs", util.getMetricPrefix("AWS/ECS"));
        assertEquals("aws_ecs_containerinsights", util.getMetricPrefix("ECS/ContainerInsights"));
        assertEquals("aws_lambda", util.getMetricPrefix("LambdaInsights"));
        assertEquals("aws_sqs", util.getMetricPrefix("AWS/SQS"));
        assertEquals("aws_s3", util.getMetricPrefix("AWS/S3"));
        assertEquals("aws_dynamodb", util.getMetricPrefix("AWS/DynamoDB"));
        assertEquals("aws_unknown", util.getMetricPrefix("AWS/Unknown"));
        verifyAll();
    }

    @Test
    void exportedMetricName() {
        expect(scrapeConfigProvider.getStandardNamespace("AWS/Lambda")).andReturn(Optional.of(lambda)).anyTimes();
        replayAll();
        assertEquals("aws_lambda_invocations_max", util.exportedMetricName(Metric.builder()
                .namespace("AWS/Lambda")
                .metricName("Invocations")
                .build(), MetricStat.Maximum));
        assertEquals("aws_lambda_invocations_avg", util.exportedMetricName(Metric.builder()
                .namespace("AWS/Lambda")
                .metricName("Invocations")
                .build(), MetricStat.Average));
        verifyAll();
    }
}
