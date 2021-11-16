
package ai.asserts.aws.cloudwatch;

import ai.asserts.aws.TestCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeQueriesRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeQueriesResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetQueryResultsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetQueryResultsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryResponse;

import java.io.FileWriter;
import java.io.PrintWriter;

public class ECSContainerMetricsFromLogEvents {
    public static void main(String[] args) throws Exception {
        try (PrintWriter printWriter = new PrintWriter(new FileWriter("container-metrics.log"))) {
            TestCredentials testCredentials = new TestCredentials();
            CloudWatchLogsClient cloudWatchClient;
            if (testCredentials.getSessionCredentials().isPresent()) {
                cloudWatchClient = CloudWatchLogsClient.builder()
                        .credentialsProvider(() -> testCredentials.getSessionCredentials().get())
                        .region(Region.US_WEST_2)
                        .build();
            } else {
                cloudWatchClient = CloudWatchLogsClient.builder()
                        .region(Region.US_WEST_2)
                        .build();
            }

            long end = System.currentTimeMillis();
            long start = end - 900_000;
            String logGroupName = "/aws/ecs/containerinsights/sample-app-fargate/performance";
            StartQueryRequest startQueryRequest = StartQueryRequest.builder()
                    .startTime(start / 1000)
                    .endTime(end / 1000)
                    .logGroupName(logGroupName)
                    .queryString("stats avg(CpuUtilized) as CPU, avg(MemoryUtilized) as Mem by bin(1m) as period, ClusterName, ServiceName, ContainerName\n" +
                            "| filter ContainerName like \"service\"\n" +
                            "| sort period, ClusterName, ServiceName, ContainerName, Mem, CPU \n" +
                            "| limit 20")
                    .build();

            StartQueryResponse startQueryResponse = cloudWatchClient.startQuery(startQueryRequest);
            System.out.println("Submitted query");
            DescribeQueriesRequest describeQueriesRequest = DescribeQueriesRequest.builder()
                    .logGroupName(logGroupName).build();
            boolean queryCompleted;
            int numAttempts = 0;
            do {
                System.out.println("Will wait for 30 seconds before polling for results...");
                Thread.sleep(30_000);
                DescribeQueriesResponse describeQueriesResponse = cloudWatchClient.describeQueries(describeQueriesRequest);
                queryCompleted = describeQueriesResponse.queries().stream()
                        .filter(queryInfo -> queryInfo.queryId().equals(startQueryResponse.queryId()))
                        .allMatch(queryInfo -> queryInfo.statusAsString().equalsIgnoreCase("complete"));
                numAttempts++;
            } while (!queryCompleted && numAttempts < 10);

            GetQueryResultsResponse queryResults = cloudWatchClient.getQueryResults(GetQueryResultsRequest.builder()
                    .queryId(startQueryResponse.queryId())
                    .build());
            if (queryResults.hasResults()) {
                System.out.println("Found some results for query. Will print results now...");
                queryResults.results().forEach(row -> printWriter.printf("%s, %s, %s, %s, %s, %s%n",
                        row.get(0).getValueForField("value", String.class).orElse(""),
                        row.get(1).getValueForField("value", String.class).orElse(""),
                        row.get(2).getValueForField("value", String.class).orElse(""),
                        row.get(3).getValueForField("value", String.class).orElse(""),
                        row.get(4).getValueForField("value", String.class).orElse("0.0"),
                        row.get(5).getValueForField("value", String.class).orElse("0.0")));
            } else {
                System.out.println("No results in query");
            }
        }
    }
}
