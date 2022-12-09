package ai.asserts.aws.resource;

import ai.asserts.aws.model.CWNamespace;
import com.google.common.collect.Sets;
import lombok.Getter;

import java.util.SortedSet;
import java.util.TreeSet;

public enum ResourceType {
    Alarm("AlarmName", CWNamespace.cloudwatch),
    ECSCluster("ClusterName", CWNamespace.ecs_svc),
    ECSService("ServiceName", CWNamespace.ecs_svc),
    ECSTaskDef("TaskDefinitionFamily", CWNamespace.ecs_svc),
    ECSTask("Task", CWNamespace.ecs_svc),
    EBSVolume("VolumeId", CWNamespace.ebs),
    EC2Instance("InstanceId", CWNamespace.ec2),
    EventBridge("RuleName", CWNamespace.ec2),
    LoadBalancer("LoadBalancer", CWNamespace.elb, "AvailabilityZone", "TargetGroup"),
    TargetGroup("TargetGroup", CWNamespace.elb, "AvailabilityZone"),
    SNSTopic("TopicName", CWNamespace.sns),
    EventBus("EventBus", CWNamespace.sns),
    EventRule("RuleName", CWNamespace.elb),
    KinesisAnalytics("KinesisAnalytics", CWNamespace.kinesis_analytics),
    KinesisDataFirehose("DeliveryStreamName", CWNamespace.firehose),
    Kinesis("StreamName", CWNamespace.kinesis),
    SQSQueue("QueueName", CWNamespace.sqs), // SQS Queue
    AutoScalingGroup("AutoScalingGroup", CWNamespace.asg), // SQS Queue
    ApiGateway("ApiGateway", CWNamespace.apigateway, "ApiId", "ApiName"),
    APIGatewayStage("Stage", CWNamespace.apigateway),
    APIGatewayRoute("Route", CWNamespace.apigateway),
    APIGatewayResource("Resource", CWNamespace.apigateway),
    APIGatewayMethod("Method", CWNamespace.apigateway),
    APIGatewayModel("Model", CWNamespace.apigateway),
    APIGatewayDeployment("Deployment", CWNamespace.apigateway),
    DynamoDBTable("TableName", CWNamespace.dynamodb, "OperationType", "Operation"), // Dynamo DB Table
    LambdaFunction("FunctionName", CWNamespace.lambda), // Lambda function
    S3Bucket("BucketName", CWNamespace.s3, "StorageType"),
    Redshift("ClusterIdentifier", CWNamespace.redshift),  // Redshift
    ElasticMapReduce("JobFlowId", CWNamespace.emr);  // Redshift

    @Getter
    private String nameDimension;
    @Getter
    private CWNamespace cwNamespace;
    private SortedSet<String> otherDimensions;

    ResourceType(String nameDimension, CWNamespace cwNamespace, String... otherDimensions) {
        this.nameDimension = nameDimension;
        this.cwNamespace = cwNamespace;
        this.otherDimensions = new TreeSet<>(Sets.newHashSet(otherDimensions));
    }
}