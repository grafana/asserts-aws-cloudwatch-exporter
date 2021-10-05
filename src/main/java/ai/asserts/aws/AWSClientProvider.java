/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClientBuilder;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClientBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.LambdaClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

@Component
@AllArgsConstructor
@SuppressWarnings("unused")
public class AWSClientProvider {
    private final TestCredentials testCredentials;

    public CloudWatchClient getCloudWatchClient(String region) {
        CloudWatchClientBuilder cloudWatchClientBuilder = CloudWatchClient.builder()
                .region(Region.of(region));
        CloudWatchClient cloudWatchClient;
        if (testCredentials.getSessionCredentials().isPresent()) {
            cloudWatchClient = cloudWatchClientBuilder
                    .credentialsProvider(() -> testCredentials.getSessionCredentials().get())
                    .build();
        } else {
            cloudWatchClient = cloudWatchClientBuilder.build();
        }
        return cloudWatchClient;
    }

    public CloudWatchLogsClient getCloudWatchLogsClient(String region) {
        CloudWatchLogsClientBuilder cloudWatchClientBuilder = CloudWatchLogsClient.builder()
                .region(Region.of(region));
        CloudWatchLogsClient cloudWatchClient;
        if (testCredentials.getSessionCredentials().isPresent()) {
            cloudWatchClient = cloudWatchClientBuilder
                    .credentialsProvider(() -> testCredentials.getSessionCredentials().get())
                    .build();
        } else {
            cloudWatchClient = cloudWatchClientBuilder.build();
        }
        return cloudWatchClient;
    }

    public LambdaClient getLambdaClient(String region) {
        LambdaClientBuilder lambdaClientBuilder = LambdaClient.builder()
                .region(Region.of(region));
        LambdaClient lambdaClient;
        if (testCredentials.getSessionCredentials().isPresent()) {
            lambdaClient = lambdaClientBuilder
                    .credentialsProvider(() -> testCredentials.getSessionCredentials().get())
                    .build();
        } else {
            lambdaClient = lambdaClientBuilder.build();
        }
        return lambdaClient;
    }

    public SqsClient getSqsClient(String region) {
        SqsClientBuilder sqsClientBuilder = SqsClient.builder()
                .region(Region.of(region));
        SqsClient sqsClient;
        if (testCredentials.getSessionCredentials().isPresent()) {
            sqsClient = sqsClientBuilder
                    .credentialsProvider(() -> testCredentials.getSessionCredentials().get())
                    .build();
        } else {
            sqsClient = sqsClientBuilder.build();
        }
        return sqsClient;
    }

    public DynamoDbClient getDynamoDBClient(String region) {
        DynamoDbClientBuilder dynamoDbClientBuilder = DynamoDbClient.builder()
                .region(Region.of(region));
        DynamoDbClient dynamoDbClient;
        if (testCredentials.getSessionCredentials().isPresent()) {
            dynamoDbClient = dynamoDbClientBuilder
                    .credentialsProvider(() -> testCredentials.getSessionCredentials().get())
                    .build();
        } else {
            dynamoDbClient = dynamoDbClientBuilder.build();
        }
        return dynamoDbClient;
    }

    public S3Client getS3Client(String region) {
        S3ClientBuilder s3ClientBuilder = S3Client.builder()
                .region(Region.of(region));
        S3Client s3Client;
        if (testCredentials.getSessionCredentials().isPresent()) {
            s3Client = s3ClientBuilder
                    .credentialsProvider(() -> testCredentials.getSessionCredentials().get())
                    .build();
        } else {
            s3Client = s3ClientBuilder.build();
        }
        return s3Client;
    }
}
