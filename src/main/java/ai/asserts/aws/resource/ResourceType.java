
package ai.asserts.aws.resource;

import com.google.common.collect.Sets;
import lombok.Getter;

import java.util.SortedSet;
import java.util.TreeSet;

public enum ResourceType {
    Alarm("AlarmName"),
    ECSCluster("ClusterName"),
    ECSService("ServiceName"),
    ECSTaskDef("TaskDefinitionFamily"),
    ECSTask("Task"),
    EventBridge("RuleName"),
    LoadBalancer("LoadBalancer", "AvailabilityZone", "TargetGroup"),
    TargetGroup("TargetGroup", "AvailabilityZone"),
    SNSTopic("TopicName"),
    EventBus("EventBus"),
    EventRule("RuleName"),
    SQSQueue("QueueName"), // SQS Queue
    AutoScalingGroup("AutoScalingGroup"), // SQS Queue
    APIGateway("ApiGateway", "ApiId", "ApiName"),
    APIGatewayStage("Stage"),
    APIGatewayRoute("Route"),
    APIGatewayResource("Resource"),
    APIGatewayMethod("Method"),
    APIGatewayModel("Model"),
    APIGatewayDeployment("Deployment"),
    DynamoDBTable("TableName", "OperationType", "Operation"), // Dynamo DB Table
    LambdaFunction("FunctionName"), // Lambda function
    S3Bucket("BucketName", "StorageType");  // S3 Bucket

    @Getter
    private String nameDimension;
    private SortedSet<String> otherDimensions;

    ResourceType(String nameDimension, String... otherDimensions) {
        this.nameDimension = nameDimension;
        this.otherDimensions = new TreeSet<>(Sets.newHashSet(otherDimensions));
    }
}
