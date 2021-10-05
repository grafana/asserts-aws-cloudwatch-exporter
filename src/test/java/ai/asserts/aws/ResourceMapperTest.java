/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ai.asserts.aws.ResourceType.DynamoDBTable;
import static ai.asserts.aws.ResourceType.LambdaFunction;
import static ai.asserts.aws.ResourceType.SQSQueue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceMapperTest {
    private ResourceMapper testClass;

    @BeforeEach
    public void setup() {
        testClass = new ResourceMapper();
    }

    @Test
    public void map_SQSQueue() {
        String arn = "arn:aws:sqs:us-west-2:342994379019:lamda-sqs-poc-input-queue";
        assertEquals(
                Optional.of(new Resource(SQSQueue, arn, "lamda-sqs-poc-input-queue")),
                testClass.map(arn)
        );
    }

    @Test
    public void map_DynamoDBTable() {
        String arn = "arn:aws:dynamodb:us-west-2:342994379019:table/auction_app_bids/stream/2021-06-01T05:03:12.707";
        assertEquals(
                Optional.of(new Resource(DynamoDBTable, arn, "auction_app_bids")),
                testClass.map(arn)
        );
    }

    @Test
    public void map_LambdaFunction() {
        String arn = "arn:aws:lambda:us-west-2:342994379019:function:lambda-poc-dynamodb-updates";
        assertEquals(
                Optional.of(new Resource(LambdaFunction, arn, "lambda-poc-dynamodb-updates")),
                testClass.map(arn)
        );

        arn = "arn:aws:lambda:us-west-2:342994379019:function:lambda-poc-dynamodb-updates:version1";
        assertEquals(
                Optional.of(new Resource(LambdaFunction, arn, "lambda-poc-dynamodb-updates")),
                testClass.map(arn)
        );
    }
}
