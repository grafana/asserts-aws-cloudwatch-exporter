
package ai.asserts.aws.resource;

import ai.asserts.aws.TaskExecutorUtil;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.asserts.aws.resource.ResourceType.APIGatewayMethod;
import static ai.asserts.aws.resource.ResourceType.APIGatewayResource;
import static ai.asserts.aws.resource.ResourceType.APIGatewayStage;
import static ai.asserts.aws.resource.ResourceType.Alarm;
import static ai.asserts.aws.resource.ResourceType.ApiGateway;
import static ai.asserts.aws.resource.ResourceType.AutoScalingGroup;
import static ai.asserts.aws.resource.ResourceType.DynamoDBTable;
import static ai.asserts.aws.resource.ResourceType.EC2Instance;
import static ai.asserts.aws.resource.ResourceType.ECSCluster;
import static ai.asserts.aws.resource.ResourceType.ECSService;
import static ai.asserts.aws.resource.ResourceType.ECSTask;
import static ai.asserts.aws.resource.ResourceType.ECSTaskDef;
import static ai.asserts.aws.resource.ResourceType.EventBus;
import static ai.asserts.aws.resource.ResourceType.Kinesis;
import static ai.asserts.aws.resource.ResourceType.KinesisAnalytics;
import static ai.asserts.aws.resource.ResourceType.KinesisDataFirehose;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static ai.asserts.aws.resource.ResourceType.LoadBalancer;
import static ai.asserts.aws.resource.ResourceType.Redshift;
import static ai.asserts.aws.resource.ResourceType.S3Bucket;
import static ai.asserts.aws.resource.ResourceType.SNSTopic;
import static ai.asserts.aws.resource.ResourceType.SQSQueue;
import static ai.asserts.aws.resource.ResourceType.TargetGroup;

@Component
public class ResourceMapper {
    public static final Pattern SQS_QUEUE_ARN_PATTERN = Pattern.compile("arn:aws:sqs:(.+?):(.+?):(.+)");
    public static final Pattern SQS_URL = Pattern.compile("https://sqs.(.+?).amazonaws.com/(.+)/(.+)");
    public static final Pattern DYNAMODB_TABLE_ARN_PATTERN =
            Pattern.compile("arn:aws:dynamodb:(.*?):(.*?):table/(.+?)(/.+)?");
    public static final Pattern LAMBDA_ARN_PATTERN = Pattern.compile("arn:aws:lambda:(.*?):(.*?):function:(.+?)(:.+)?");
    public static final Pattern S3_ARN_PATTERN = Pattern.compile("arn:aws:s3:(.*?):(.*?):(.+?)");
    public static final Pattern SNS_ARN_PATTERN = Pattern.compile("arn:aws:sns:(.+?):(.+?):(.+)");
    public static final Pattern EVENTBUS_ARN_PATTERN = Pattern.compile("arn:aws:events:(.+?):(.+?):event-bus/(.+)");
    public static final Pattern ECS_CLUSTER_PATTERN = Pattern.compile("arn:aws:ecs:(.+?):(.+?):cluster/(.+)");
    public static final Pattern ECS_SERVICE_PATTERN = Pattern.compile("arn:aws:ecs:(.+?):(.+?):service/(.+?)/(.+)");
    public static final Pattern ECS_TASK_DEFINITION_PATTERN =
            Pattern.compile("arn:aws:ecs:(.+?):(.+?):task-definition/(.+)");
    public static final Pattern ECS_TASK_PATTERN = Pattern.compile("arn:aws:ecs:(.+?):(.+?):task/(.+?)/(.+)");

    /**
     * See https://docs.aws.amazon.com/elasticloadbalancing/latest/userguide/load-balancer-authentication-access
     * -control.html
     */
    public static final Pattern LB_PATTERN =
            Pattern.compile("arn:aws:elasticloadbalancing:(.+?):(.+?):loadbalancer/((.+?)/)?(.+?)(/(.+))?");
    public static final Pattern ASG_PATTERN =
            Pattern.compile("arn:aws:autoscaling:(.+?):(.+?):autoScalingGroup:(.+?):autoScalingGroupName/(.+)");
    public static final Pattern APIGATEWAY_PATTERN =
            Pattern.compile("arn:.+?:apigateway:(.+?):(.*?):/(restapis|apis)/(.+)");
    public static final Pattern APIGATEWAY_STAGE_PATTERN =
            Pattern.compile("arn:.+?:apigateway:(.+?):(.*?):/(restapis|apis)/(.+?)/stages/(.+)");
    public static final Pattern APIGATEWAY_RESOURCE_PATTERN =
            Pattern.compile("arn:.+?:apigateway:(.+?):(.*?):/(restapis|apis)/(.+?)/resources/(.+)");
    public static final Pattern APIGATEWAY_METHOD_PATTERN =
            Pattern.compile("arn:.+?:apigateway:(.+?):(.*?):/(restapis|apis)/(.+?)/resources/(.+)/methods/(.+)");
    public static final Pattern TARGET_GROUP_PATTERN =
            Pattern.compile("arn:aws:elasticloadbalancing:(.+?):(.+?):targetgroup/(.+?)/(.+)");
    public static final Pattern ALARM_PATTERN = Pattern.compile("arn:aws:cloudwatch:(.+?):(.+?):alarm:(.+)");
    public static final Pattern EC2_PATTERN = Pattern.compile("arn:aws:ec2:(.+?):(.+?):instance/(.+)");
    public static final Pattern KINESIS_PATTERN = Pattern.compile("arn:aws:kinesis:(.+?):(.+?):stream/(.+)");
    public static final Pattern KINESIS_ANALYTICS_PATTERN =
            Pattern.compile("arn:aws:kinesisanalytics:(.+?):(.+?):application/(.+)");
    public static final Pattern KINESIS_FIREHOSE_PATTERN =
            Pattern.compile("arn:aws:firehose:(.+?):(.+?):deliverystream/(.+)");
    public static final Pattern REDSHIFT_PATTERN = Pattern.compile("arn:aws:redshift:(.+?):(.+?):cluster/(.+)");

    private final List<Mapper> mappers;

    public ResourceMapper(TaskExecutorUtil taskExecutorUtil) {
        this.mappers = new ImmutableList.Builder<Mapper>()
                .add(arn -> {
                    if (arn.contains(":redshift:") && arn.contains(":cluster/")) {
                        Matcher matcher = REDSHIFT_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(Redshift)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":firehose:") && arn.contains(":deliverystream/")) {
                        Matcher matcher = KINESIS_FIREHOSE_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(KinesisDataFirehose)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":kinesisanalytics:") && arn.contains(":application/")) {
                        Matcher matcher = KINESIS_ANALYTICS_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(KinesisAnalytics)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":kinesis:") && arn.contains(":stream/")) {
                        Matcher matcher = KINESIS_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(Kinesis)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":ec2:") && arn.contains(":instance")) {
                        Matcher matcher = EC2_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(EC2Instance)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":alarm:")) {
                        Matcher matcher = ALARM_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(Alarm)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":sqs")) {
                        Matcher matcher = SQS_QUEUE_ARN_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(SQSQueue)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
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
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(DynamoDBTable)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
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
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(LambdaFunction)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
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
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(S3Bucket)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
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
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(SNSTopic)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
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
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(EventBus)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
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
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(ECSCluster)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
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
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(ECSService)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(4))
                                    .childOf(Resource.builder()
                                            .type(ECSCluster)
                                            .region(matcher.group(1))
                                            .account(matcher.group(2))
                                            .name(matcher.group(3))
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
                            String[] nameAndVersion = matcher.group(3).split(":");
                            return Optional.of(builder
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(ECSTaskDef)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
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
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(ECSTask)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(4))
                                    .childOf(Resource.builder()
                                            .account(matcher.group(2))
                                            .region(matcher.group(1))
                                            .type(ECSCluster)
                                            .name(matcher.group(3))
                                            .build())
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains("arn:aws:elasticloadbalancing") && arn.contains("loadbalancer")) {
                        Matcher matcher = LB_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(LoadBalancer)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .subType(matcher.group(4))
                                    .name(matcher.group(5))
                                    .id(matcher.group(7))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains("arn:aws:elasticloadbalancing") && arn.contains("targetgroup")) {
                        Matcher matcher = TARGET_GROUP_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(TargetGroup)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
                                    .id(matcher.group(4))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains("https://sqs")) {
                        Matcher matcher = SQS_URL.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(SQSQueue)
                                    .arn(String.format("arn:aws:sqs:%s:%s:%s", matcher.group(1), matcher.group(2),
                                            matcher.group(3)))
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(3))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains("arn:aws:autoscaling:")) {
                        Matcher matcher = ASG_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(AutoScalingGroup)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .id(matcher.group(3))
                                    .name(matcher.group(4))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":apigateway:") && arn.contains("/methods/")) {
                        Matcher matcher = APIGATEWAY_METHOD_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(APIGatewayMethod)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(6))
                                    .childOf(Resource.builder()
                                            .type(ApiGateway)
                                            .region(matcher.group(1))
                                            .account(matcher.group(2))
                                            .subType(matcher.group(3))
                                            .name(matcher.group(4))
                                            .build())
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":apigateway:") && arn.contains("/stages/")) {
                        Matcher matcher = APIGATEWAY_STAGE_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(APIGatewayStage)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(5))
                                    .childOf(Resource.builder()
                                            .type(ApiGateway)
                                            .region(matcher.group(1))
                                            .account(matcher.group(2))
                                            .subType(matcher.group(3))
                                            .name(matcher.group(4))
                                            .build())
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":apigateway:") && arn.contains("/resources/")) {
                        Matcher matcher = APIGATEWAY_RESOURCE_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(APIGatewayResource)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .name(matcher.group(5))
                                    .childOf(Resource.builder()
                                            .type(ApiGateway)
                                            .region(matcher.group(1))
                                            .account(matcher.group(2))
                                            .subType(matcher.group(3))
                                            .name(matcher.group(4))
                                            .build())
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .add(arn -> {
                    if (arn.contains(":apigateway:")) {
                        Matcher matcher = APIGATEWAY_PATTERN.matcher(arn);
                        if (matcher.matches()) {
                            return Optional.of(Resource.builder()
                                    .tenant(taskExecutorUtil.getAccountDetails().getTenant())
                                    .type(ApiGateway)
                                    .arn(arn)
                                    .region(matcher.group(1))
                                    .account(matcher.group(2))
                                    .subType(matcher.group(3))
                                    .name(matcher.group(4))
                                    .build());
                        }
                    }
                    return Optional.empty();
                })
                .build();
    }

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

