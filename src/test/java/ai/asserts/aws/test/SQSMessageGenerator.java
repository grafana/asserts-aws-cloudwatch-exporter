
package ai.asserts.aws.test;

import ai.asserts.aws.TestCredentials;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static ai.asserts.aws.test.SQSMessageGenerator.Problem.normal;

public class SQSMessageGenerator {
    @SuppressWarnings("BusyWait")
    public static void main(String[] args) throws Exception {
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

        List<TestMessage> commands = new ArrayList<>();

        // Stay normal for 10 minutes
        // Then cause latency warning 10 minutes
        // Then cause latency critical 10 minutes
        // Then become normal 10 minutes
        // Then start memory saturation warning 10 minutes
        // Then start memory saturation critical 10 minutes
        // Then become normal 10 minutes
        // Then cause error spike level1 10 minutes
        // Increase error rate 10 minutes
        commands.add(new TestMessage(normal, 0, 120L));
//        commands.add(new TestMessage(memory, 300, 5L));
//        commands.add(new TestMessage(memory, 600, 10L));
//        commands.add(new TestMessage(memory, 800, 10L));
//        commands.add(new TestMessage(normal, 0, 430L));
//        commands.add(new TestMessage(error, 30, 10L));
//        commands.add(new TestMessage(normal, 0, 10L));
//        commands.add(new TestMessage(latency, 2250, 10L));
//        commands.add(new TestMessage(latency, 3250, 10L));
//        commands.add(new TestMessage(latency, 4750, 10L));
//        commands.add(new TestMessage(normal, 0, 30L));


        int i = 0;
        TestMessage current = commands.get(0);
        current.start();
        do {
            if (!current.isActive()) {
                int next = ++i;
                current = commands.get(next % commands.size());
                current.start();
            }
            System.out.println(current);
            List<SendMessageBatchRequestEntry> messages = new ArrayList<>();
            int numMessagesPerBatch = 5;
            for (int j = 0; j < numMessagesPerBatch; j++) {
                messages.add(buildMessage(UUID.randomUUID().toString(), current));
            }

            Stream.of("NodeJSPerf-WithLayer").forEach(qName -> {
                SendMessageBatchRequest batchRequest = SendMessageBatchRequest.builder()
                        .queueUrl("https://sqs.us-west-2.amazonaws.com/342994379019/" + qName)
                        .entries(messages.toArray(new SendMessageBatchRequestEntry[0]))
                        .build();
                SendMessageBatchResponse sendMessageBatchResponse = sqsClient.sendMessageBatch(batchRequest);
                if (sendMessageBatchResponse.hasSuccessful()) {
                    System.out.println("Successfully sent batch message with " + numMessagesPerBatch + " messages to queue=" + qName);
                }
            });
            try {
                Thread.sleep(15000L);
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        } while (true);
    }

    private static ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static SendMessageBatchRequestEntry buildMessage(String messageId, TestMessage testMessage) throws Exception {
        String messageBody = objectMapper.writeValueAsString(testMessage);
        return SendMessageBatchRequestEntry.builder()
                .id("" + messageId)
                .messageBody(messageBody)
                .build();
    }

    public static class TestMessage {
        private final Problem problem;
        private final double measure;
        @JsonIgnore
        private final long durationMinutes;
        @JsonIgnore
        private long startTime;
        @JsonIgnore
        private long endTime;

        public TestMessage(Problem problem, double measure, long durationMinutes) {
            this.problem = problem;
            this.measure = measure;
            this.durationMinutes = durationMinutes;
        }

        public Problem getProblem() {
            return problem;
        }


        public double getMeasure() {
            return measure;
        }

        public long getDurationMinutes() {
            return durationMinutes;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getEndTime() {
            return endTime;
        }

        public void start() {
            startTime = Instant.now().toEpochMilli();
            endTime = Instant.now().plusSeconds(durationMinutes * 60).toEpochMilli();
        }

        @JsonIgnore
        public boolean isActive() {
            return Instant.now().toEpochMilli() < endTime;
        }

        @Override
        public String toString() {
            return "TestMessage{" +
                    "problem=" + problem +
                    ", measure=" + measure +
                    ", durationMinutes=" + durationMinutes +
                    ", startTime=" + startTime +
                    ", endTime=" + endTime +
                    '}';
        }
    }

    public enum Problem {
        normal, memory, latency, error
    }
}
