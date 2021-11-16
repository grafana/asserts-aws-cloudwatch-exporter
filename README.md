# aws-exporter
Standalone exporter to export
[AWS CloudWatch Metrics](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/cloudwatch_concepts.html) and
Logs as prometheus metrics. This exporter uses AWS APIs and fetches both metadata and metric data

# License
This software is available under [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0.txt)

# Configuration
The exporter needs to be configured to extract metrics for one or more AWS Service types. AWS Service types map to a
namespace in CloudWatch. The supported names are :-

| AWS Service | CloudWatch Namespace | List of Metrics |
|--------------|--------------|------------------------|
| Lambda | lambda | [Lambda metrics](https://docs.aws.amazon.com/lambda/latest/dg/monitoring-metrics.html)|
| SQS Queue | sqs| [SQS Metrics](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-available-cloudwatch-metrics.html)|
| DynamoDB | dynamodb | [DynamoDB Metrics](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/metrics-dimensions.html)|
| S3 | s3| [S3 Metrics](https://docs.aws.amazon.com/AmazonS3/latest/userguide/metrics-dimensions.html)|
| ECS Metrics| ecs_svc| [ECS Metrics](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/cloudwatch-metrics.html#available_cloudwatch_metrics)|
| ECS Container Insights| ecs_containerinsights| [ECS Container Metrics](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/Container-Insights-metrics-ECS.html)|

**Time interval configurations**

The exporter scrapes metrics periodically at a frequency determined by the **scrapeInterval**. In each scrape the
exporter requests an aggregate statistic for a specific **period**. The unit for time interval configuration is
`second`. The default scrape interval is `60` seconds. The default period is `60` seconds. The time intervals can be
configured at in the following different ways

* **Global** This will apply to all metrics across all namespaces
* **Namespace** This will apply to all metrics for a specific namespace
* **Metric** This will apply to a specific metric in a specific namespace

**Number of metric samples**

The number of metric samples retrieved in each scrape will be `scrapeInterval / period` if ` scrapeInterval > period `
or `1` if `period > scrapeInterval`

| Scrape Interval | Period | Number of Samples in each scrape|
|-----------------|--------|---------------------------------|
| 300 | 60 | 5|
| 60 | 300 | 1|


**Sample Configuration**
```
regions:
  - us-west-2
namespaces:
  - name: lambda
    scrapeInterval: 60
    period: 60
    dimensionFilters:
      FunctionName: (.+)
    metrics:
      - name: Invocations
        stats:
          - Sum
      - name: Errors
        stats:
          - Sum
      - name: DeadLetterErrors
        stats:
          - Sum
      - name: Throttles
        stats:
          - Sum
      - name: DestinationDeliveryFailures
        stats:
          - Sum
      - name: Duration
        stats:
          - p99
          - Average
          - Maximum
      - name: ProvisionedConcurrencyInvocations
        stats:
          - Sum
      - name: ProvisionedConcurrencySpilloverInvocations
        stats:
          - Sum
      - name: ConcurrentExecutions
        stats:
          - Average
          - Maximum
      - name: ProvisionedConcurrentExecutions
        stats:
          - Average
      - name: UnreservedConcurrentExecutions
        stats:
          - Average
      - name: IteratorAge
        stats:
          - Maximum
  - name: sqs
    metrics:
      - name: NumberOfMessagesDeleted
        stats:
          - Sum
      - name: NumberOfMessagesSent
        stats:
          - Sum
      - name: ApproximateNumberOfMessagesVisible
        stats:
          - Average
      - name: SentMessageSize
        stats:
          - Average
      - name: NumberOfEmptyReceives
        stats:
          - Sum
      - name: ApproximateAgeOfOldestMessage
        stats:
          - Maximum
  - name: dynamodb
    metrics:
      - name: ConsumedReadCapacityUnits
        stats:
          - Maximum
          - Average
      - name: ConsumedWriteCapacityUnits
        stats:
          - Maximum
          - Average
      - name: ProvisionedReadCapacityUnits
        stats:
          - Average
      - name: ProvisionedWriteCapacityUnits
        stats:
          - Average
      - name: ReadThrottleEvents
        stats:
          - Sum
      - name: WriteThrottleEvents
        stats:
          - Sum
  - name: s3
    period: 86400
    scrapeInterval: 86400
    metrics:
      - name: NumberOfObjects
        stats:
          - Average
      - name: BucketSizeBytes
        stats:
          - Average
  - name: lambdainsights
    scrapeInterval: 60
    period: 60
    dimensionFilters:
      FunctionName: (.+)
    metrics:
      - name: memory_utilization
        stats:
          - Average
      - name: total_memory
        stats:
          - Average
      - name: used_memory_max
        stats:
          - Average
      - name: cpu_total_time
        stats:
          - Sum
      - name: tx_bytes
        stats:
          - Sum
      - name: rx_bytes
        stats:
          - Sum
  - name: ecs_svc
    scrapeInterval: 60
    period: 60
    dimensionFilters:
      FunctionName: (.+)
    metrics:
      - name: MemoryUtilization
        stats:
          - Average
          - Maximum
      - name: CPUUtilization
        stats:
          - Average
          - Maximum
  - name: ecs_containerinsights
    scrapeInterval: 60
    period: 60
    dimensionFilters:
      FunctionName: (.+)
    metrics:
      - name: MemoryReserved
        stats:
          - Average
          - Maximum
      - name: MemoryUtilized
        stats:
          - Average
          - Maximum
      - name: CpuReserved
        stats:
          - Average
          - Maximum
      - name: CpuUtilized
        stats:
          - Average
          - Maximum
      - name: NetworkRxBytes
        stats:
          - Sum
      - name: NetworkTxBytes
        stats:
          - Sum
      - name: StorageReadBytes
        stats:
          - Sum
      - name: StorageWriteBytes
        stats:
          - Sum
```

You can specify one or more regions. The specified configuration will be applicable to all regions. If different regions
need different configurations then a different instance of the exporter will need to be run for each set of configuration

# Metric names

All metric and label names are in snake case. The metrics will be prefixed with `aws` followed by the AWS Service name.
For example the `Sum` statistic for the `Invocations` metric in the `lambda` namespace will be exported as
`aws_lambda_invocations_sum`

# Label names

**Region**

The aws region will be exported as the label `region`. For e.g. `region="us-west-2"`

**CloudWatch metric dimension**

All dimensions in a metric will be exported as labels. For e.g. for the `aws_lambda_invocations_sum`, the Lambda
function name is available as the dimension `FunctionName` in the CloudWatch metric. This will be exported as
`d_function_name`. The dimension name will be converted to snake case and prefixed with a `d_`

**Tags**

If the AWS Resource has tags, the tags will be discovered and reported as labels with a prefix `tag_`. For example if
the Lambda function has a tag `env` with a value of `dev`, it will be exported as `tag_env="dev"`

# Exporter internal metrics
The exporter also exports the following metrics to enable monitoring itself

|Metric Name|Description|
|---|---|
|cw_scrape_milliseconds| Latency of all AWS API calls|
|cw_scrape_interval_seconds|The scrape interval metric for each metric|
|cw_scrape_period_seconds| The statistic period for each metric|

# Running it locally
```
cp conf/cloudwatch_scrape_config_sample.yml ./cloudwatch_scrape_config.yml
./bin/aws-exporter
```

The exporter listens on port `8010` by default. The metrics can be scraped from
`http://localhost:8010/aws-exporter/actuator/prometheus`. Here is a sample output

Here is a sample output of metrics
```
# HELP aws_sqs_number_of_messages_deleted_sum 
# TYPE aws_sqs_number_of_messages_deleted_sum gauge
aws_sqs_number_of_messages_deleted_sum{d_queue_name="lamda-sqs-poc-input-queue",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 0.0 1633625100000
aws_sqs_number_of_messages_deleted_sum{d_queue_name="lamda-sqs-poc-output-queue",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 0.0 1633625100000
aws_sqs_number_of_messages_deleted_sum{d_queue_name="aws-lambda-poc-destination-queue",region="us-west-2",} 0.0 1633625220000
aws_sqs_number_of_messages_deleted_sum{d_queue_name="lamda-sqs-poc-dl-queue",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 0.0 1633625220000

# HELP aws_lambda_errors_sum 
# TYPE aws_lambda_errors_sum gauge
aws_lambda_errors_sum{d_function_name="first-lambda-function",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 0.0 1633625220000
aws_lambda_errors_sum{region="us-west-2",} 0.0 1633625220000
aws_lambda_errors_sum{d_function_name="first-lambda-function",d_resource="first-lambda-function:version2",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 0.0 1633625220000
aws_lambda_errors_sum{d_executed_version="2",d_function_name="first-lambda-function",d_resource="first-lambda-function:version2",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 0.0 1633625220000
aws_lambda_errors_sum{d_function_name="first-lambda-function",d_resource="first-lambda-function",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 0.0 1633625220000

# HELP aws_lambda_concurrent_executions_sum 
# TYPE aws_lambda_concurrent_executions_sum gauge
aws_lambda_concurrent_executions_sum{d_function_name="first-lambda-function",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 18.0 1633625220000
aws_lambda_concurrent_executions_sum{region="us-west-2",} 18.0 1633625220000
aws_lambda_concurrent_executions_sum{d_function_name="first-lambda-function",d_resource="first-lambda-function:version1",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 2.0 1633625220000
aws_lambda_concurrent_executions_sum{d_executed_version="2",d_function_name="first-lambda-function",d_resource="first-lambda-function:version2",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 4.0 1633625220000
aws_lambda_concurrent_executions_sum{d_function_name="first-lambda-function",d_resource="first-lambda-function",region="us-west-2",tag_asserts_aws_resource="tag_for_discovery",} 4.0 1633625220000
```

# AWS IAM Permissions
The following IAM permissions need to be configured for the exporter
```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "elasticfilesystem:*"
            ],
            "Resource": "*"
        },
        {
            "Effect": "Allow",
            "Action": "apigateway:GET",
            "Resource": "arn:aws:apigateway:*::/restapis/*/resources/*/methods/*"
        },
        {
            "Effect": "Allow",
            "Action": "apigateway:GET",
            "Resource": "arn:aws:apigateway:*::/restapis/*"
        },
        {
            "Effect": "Allow",
            "Action": "apigateway:GET",
            "Resource": [
                "arn:aws:apigateway:*::/restapis/*/deployments/*",
                "arn:aws:apigateway:*::/restapis/*/stages/*",
                "arn:aws:apigateway:*::/restapis/*/resources",
                "arn:aws:apigateway:*::/restapis/*/deployments",
                "arn:aws:apigateway:*::/restapis/*/resources/*/methods/*/integration",
                "arn:aws:apigateway:*::/restapis/*/stages",
                "arn:aws:apigateway:*::/restapis"
            ]
        },
        {
            "Effect": "Allow",
            "Action": "apigateway:GET",
            "Resource": "arn:aws:apigateway:*::/apis/*/stages/*"
        },
        {
            "Effect": "Allow",
            "Action": "apigateway:GET",
            "Resource": [
                "arn:aws:apigateway:*::/apis/*/routes/*",
                "arn:aws:apigateway:*::/apis/*/stages",
                "arn:aws:apigateway:*::/apis/*/integrations/*",
                "arn:aws:apigateway:*::/apis/*/integrations",
                "arn:aws:apigateway:*::/apis/*/routes",
                "arn:aws:apigateway:*::/apis/*/stages/*/routesettings/*",
                "arn:aws:apigateway:*::/apis/*/deployments",
                "arn:aws:apigateway:*::/apis/*/deployments/*",
                "arn:aws:apigateway:*::/apis"
            ]
        },
        {
            "Effect": "Allow",
            "Action": "apigateway:GET",
            "Resource": "arn:aws:apigateway:*::/apis/*"
        },
        {
            "Effect": "Allow",
            "Action": [
                "lambda:ListProvisionedConcurrencyConfigs",
                "lambda:ListFunctionEventInvokeConfigs",
                "tag:GetResources",
                "lambda:ListFunctions",
                "lambda:ListVersionsByFunction",
                "cloudwatch:GetMetricData",
                "lambda:ListAliases",
                "lambda:ListEventSourceMappings",
                "cloudwatch:ListMetrics",
                "lambda:GetAccountSettings"
            ],
            "Resource": "*"
        },
        {
            "Effect": "Allow",
            "Action": [
                "logs:FilterLogEvents",
                "apigateway:GET"
            ],
            "Resource": [
                "arn:aws:apigateway:*::/restapis/*/resources/*",
                "arn:aws:logs:*:123456789123:log-group:*"
            ]
        }
    ]
}

```
