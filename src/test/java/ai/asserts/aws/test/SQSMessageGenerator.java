/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.test;

import ai.asserts.aws.TestCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.util.ArrayList;
import java.util.List;

public class SQSMessageGenerator {
    @SuppressWarnings("BusyWait")
    public static void main(String[] args) {
        TestCredentials testCredentials = new TestCredentials();
        SqsClient sqsClient;
        if (testCredentials.getSessionCredentials().isPresent()) {
            sqsClient = SqsClient.builder()
                    .credentialsProvider(() -> testCredentials.getSessionCredentials().get())
                    .region(Region.US_WEST_2)
                    .build();
        } else {
            sqsClient = SqsClient.builder()
                    .region(Region.US_WEST_2)
                    .build();
        }

        Counter messageId = new Counter();

        do {
            List<SendMessageBatchRequestEntry> messages = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                messages.add(buildMessage(messageId));
            }

            SendMessageBatchRequest batchRequest = SendMessageBatchRequest.builder()
                    .queueUrl("https://sqs.us-west-2.amazonaws.com/342994379019/lamda-sqs-poc-input-queue")
                    .entries(messages.toArray(new SendMessageBatchRequestEntry[0]))
                    .build();
            SendMessageBatchResponse sendMessageBatchResponse = sqsClient.sendMessageBatch(batchRequest);
            if (sendMessageBatchResponse.hasSuccessful()) {
                System.out.println("Successfully send batch message with 5 messages");
            }
            try {
                Thread.sleep(15000L);
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        } while (true);
    }

    private static SendMessageBatchRequestEntry buildMessage(Counter messageId) {
        return SendMessageBatchRequestEntry.builder()
                .id("" + messageId.next())
                .messageBody("{\n" +
                        "  \"key1\": \"value1\",\n" +
                        "  \"key2\": \"value2\",\n" +
                        "  \"key3\": \"value3\"\n" +
                        "}")
                .build();
    }

    public static class Counter {
        long messageId = 0L;

        public long next() {
            return ++messageId;
        }
    }
}
