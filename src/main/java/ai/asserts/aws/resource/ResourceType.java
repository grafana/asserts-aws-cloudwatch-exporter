
package ai.asserts.aws.resource;

public enum ResourceType {
    ECSCluster,
    ECSService,
    ECSTaskDef,
    ECSTask,
    ALB,
    SNSTopic,
    EventBus,
    SQSQueue, // SQS Queue
    AutoScalingGroup, // SQS Queue
    APIGateway,
    APIGatewayStage,
    DynamoDBTable, // Dynamo DB Table
    LambdaFunction, // Lambda function
    S3Bucket  // S3 Bucket
}
