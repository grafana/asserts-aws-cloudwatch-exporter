
package ai.asserts.aws.resource;

import ai.asserts.aws.MetricNameUtil;
import com.google.common.collect.ImmutableList;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.asserts.aws.MetricNameUtil.TENANT;
import static java.lang.String.format;

@Getter
@EqualsAndHashCode()
@Builder
@ToString
public class Resource {
    private final ResourceType type;
    /**
     * Some ARNs have uuid. See {@link ResourceMapper#ASG_PATTERN}
     */
    private final String id;

    /**
     * Some ARNs have sub type of resource. See {@link ResourceMapper#LB_PATTERN}
     */
    private final String subType;
    private final String name;
    private final String region;
    private final String account;
    private final String tenant;

    /**
     * Some ARNs have version of the resource. See {@link ResourceMapper#LAMBDA_ARN_PATTERN}
     */
    private final String version;

    /**
     * Some resources also have information about their parent resource.
     */
    private final Resource childOf;
    @EqualsAndHashCode.Exclude
    private final String arn;

    @EqualsAndHashCode.Exclude
    @Setter
    @Builder.Default
    private List<Tag> tags = new ArrayList<>();

    @Setter
    private Optional<Tag> envTag;

    public String getIdOrName() {
        if (id != null) {
            return id;
        }
        return name;
    }

    public boolean matches(Metric metric) {
        List<List<Dimension>> toBeMatched = metricDimensions();
        return metric.hasDimensions() && toBeMatched.stream().anyMatch(list -> metric.dimensions().containsAll(list));
    }


    public void addLabels(Map<String, String> labels, String prefix) {
        prefix = StringUtils.hasLength(prefix) ? prefix + "_" : "";
        if (tenant != null) {
            labels.put(TENANT, tenant);
        }
        if (account != null) {
            labels.put(format("%saccount", prefix), account);
        }
        labels.put(format("%sregion", prefix), region);
        labels.put(format("%stype", prefix), type.name());
        if (subType != null) {
            labels.put(format("%ssubtype", prefix), subType);
        }
        labels.put(format("%sname", prefix), name);
        if (id != null) {
            labels.put(format("%sid", prefix), id);
        }
    }

    public void addTagLabels(Map<String, String> labels, MetricNameUtil metricNameUtil) {
        if (!CollectionUtils.isEmpty(tags)) {
            tags.forEach(tag -> labels.put(format("tag_%s", metricNameUtil.toSnakeCase(tag.key())), tag.value()));
        }
    }

    public void addEnvLabel(Map<String, String> labels, MetricNameUtil metricNameUtil) {
        if (envTag != null) {
            envTag.ifPresent(tag -> labels.put("tag_" + metricNameUtil.toSnakeCase(tag.key()), tag.value()));
        }
    }

    private List<List<Dimension>> metricDimensions() {
        switch (type) {
            case LambdaFunction:
                return ImmutableList.of(
                        ImmutableList.of(Dimension.builder().name("FunctionName").value(name).build()),
                        ImmutableList.of(Dimension.builder().name("function_name").value(name).build())
                );
            case SQSQueue:
                return ImmutableList.of(
                        ImmutableList.of(Dimension.builder().name("QueueName").value(name).build())
                );
            case DynamoDBTable:
                return ImmutableList.of(
                        ImmutableList.of(Dimension.builder().name("TableName").value(name).build())
                );
            case S3Bucket:
                return ImmutableList.of(
                        ImmutableList.of(Dimension.builder().name("BucketName").value(name).build())
                );
            case ECSCluster:
                return ImmutableList.of(
                        ImmutableList.of(Dimension.builder().name("ClusterName").value(name).build())
                );
            case ECSService:
                return ImmutableList.of(
                        ImmutableList.of(
                                Dimension.builder().name("ServiceName").value(name).build(),
                                Dimension.builder().name("ClusterName").value(childOf.name).build())
                );
            case ECSTaskDef:
                return ImmutableList.of(
                        ImmutableList.of(Dimension.builder().name("TaskDefinitionFamily").value(name).build())
                );
            default:
                return ImmutableList.of();
        }
    }
}
