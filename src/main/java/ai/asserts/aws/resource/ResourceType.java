package ai.asserts.aws.resource;

import ai.asserts.aws.cloudwatch.model.CWNamespace;
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
    KinesisAnalytics("KinesisAnalytics", CWNamespace.kinesis),
    KinesisDataFirehose("KinesisAnalytics", CWNamespace.kinesis),
    Kinesis("Kinesis", CWNamespace.kinesis),
    SQSQueue("QueueName", CWNamespace.sqs), // SQS Queue
    AutoScalingGroup("AutoScalingGroup", CWNamespace.asg), // SQS Queue
    APIGateway("ApiGateway", CWNamespace.apigateway, "ApiId", "ApiName"),
    APIGatewayStage("Stage", CWNamespace.apigateway),
    APIGatewayRoute("Route", CWNamespace.apigateway),
    APIGatewayResource("Resource", CWNamespace.apigateway),
    APIGatewayMethod("Method", CWNamespace.apigateway),
    APIGatewayModel("Model", CWNamespace.apigateway),
    APIGatewayDeployment("Deployment", CWNamespace.apigateway),
    DynamoDBTable("TableName", CWNamespace.dynamodb, "OperationType", "Operation"), // Dynamo DB Table
    LambdaFunction("FunctionName", CWNamespace.lambda), // Lambda function
    S3Bucket("BucketName", CWNamespace.s3, "StorageType");  // S3 Bucket

    @Getter
    private String nameDimension;
    private CWNamespace cwNamespace;
    private SortedSet<String> otherDimensions;

    ResourceType(String nameDimension, CWNamespace cwNamespace, String... otherDimensions) {
        this.nameDimension = nameDimension;
        this.cwNamespace = cwNamespace;
        this.otherDimensions = new TreeSet<>(Sets.newHashSet(otherDimensions));
    }
}