
package ai.asserts.aws.resource;

import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.asserts.aws.resource.ResourceType.DynamoDBTable;
import static ai.asserts.aws.resource.ResourceType.ECSCluster;
import static ai.asserts.aws.resource.ResourceType.ECSService;
import static ai.asserts.aws.resource.ResourceType.ECSTask;
import static ai.asserts.aws.resource.ResourceType.ECSTaskDef;
import static ai.asserts.aws.resource.ResourceType.EventBus;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static ai.asserts.aws.resource.ResourceType.S3Bucket;
import static ai.asserts.aws.resource.ResourceType.SNSTopic;
import static ai.asserts.aws.resource.ResourceType.SQSQueue;

@Component
public class ResourceMapper {
    private static final Pattern SQS_QUEUE_ARN_PATTERN = Pattern.compile("arn:aws:sqs:(.*?):.*?:(.+)");
    private static final Pattern DYNAMODB_TABLE_ARN_PATTERN = Pattern.compile("arn:aws:dynamodb:(.*?):.*?:table/(.+?)(/.+)?");
    private static final Pattern LAMBDA_ARN_PATTERN = Pattern.compile("arn:aws:lambda:(.*?):.*?:function:(.+?)(:.+)?");
    private static final Pattern S3_ARN_PATTERN = Pattern.compile("arn:aws:s3:(.*?):.*?:(.+?)");
    private static final Pattern SNS_ARN_PATTERN = Pattern.compile("arn:aws:sns:(.+?):.+?:(.+)");
    private static final Pattern EVENTBUS_ARN_PATTERN = Pattern.compile("arn:aws:events:(.+?):.+?:event-bus/(.+)");
    private static final Pattern ECS_CLUSTER_PATTERN = Pattern.compile("arn:aws:ecs:(.+?):.+?:cluster/(.+)");
    private static final Pattern ECS_SERVICE_PATTERN = Pattern.compile("arn:aws:ecs:(.+?):.+?:service/(.+?)/(.+)");
    private static final Pattern ECS_TASK_DEFINITION_PATTERN = Pattern.compile("arn:aws:ecs:(.+?):.+?:task-definition/(.+)");
    private static final Pattern ECS_TASK_PATTERN = Pattern.compile("arn:aws:ecs:(.+?):.+?:task/.+?/(.+)");

    private final List<Mapper> mappers = new ImmutableList.Builder<Mapper>()
            .add(arn -> {
                if (arn.contains(":sqs")) {
                    Matcher matcher = SQS_QUEUE_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(SQSQueue)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains(":dynamodb") && arn.contains(":table/")) {
                    Matcher matcher = DYNAMODB_TABLE_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(DynamoDBTable)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains(":lambda") && arn.contains(":function:")) {
                    Matcher matcher = LAMBDA_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(LambdaFunction)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains(":s3")) {
                    Matcher matcher = S3_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(S3Bucket)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains(":sns")) {
                    Matcher matcher = SNS_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(SNSTopic)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains(":events") && arn.contains(":event-bus/")) {
                    Matcher matcher = EVENTBUS_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(EventBus)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains(":ecs") && arn.contains(":cluster/")) {
                    Matcher matcher = ECS_CLUSTER_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(ECSCluster)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains(":ecs") && arn.contains(":service/")) {
                    Matcher matcher = ECS_SERVICE_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(ECSService)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(3))
                                .childOf(Resource.builder()
                                        .type(ECSCluster)
                                        .region(matcher.group(1))
                                        .name(matcher.group(2))
                                        .build())
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains(":ecs") && arn.contains(":task-definition/")) {
                    Matcher matcher = ECS_TASK_DEFINITION_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        Resource.ResourceBuilder builder = Resource.builder();
                        String[] nameAndVersion = matcher.group(2).split(":");
                        return Optional.of(builder
                                .type(ECSTaskDef)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(nameAndVersion[0])
                                .version(nameAndVersion.length == 2 ? nameAndVersion[1] : null)
                                .build());
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains(":ecs") && arn.contains(":task/")) {
                    Matcher matcher = ECS_TASK_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(Resource.builder()
                                .type(ECSTask)
                                .arn(arn)
                                .region(matcher.group(1))
                                .name(matcher.group(2))
                                .build());
                    }
                }
                return Optional.empty();
            })
            .build();

    public Optional<Resource> map(String arn) {
        return mappers.stream()
                .map(mapper -> mapper.get(arn))
                .filter(Optional::isPresent)
                .findFirst().orElse(Optional.empty());
    }

    public interface Mapper {
        Optional<Resource> get(String arn);
    }
}

