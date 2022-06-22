# aws-exporter
Standalone exporter to export
[AWS CloudWatch Metrics](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/cloudwatch_concepts.html) and
Logs as prometheus metrics. This exporter uses AWS APIs and fetches both metadata and metric data

# License
This software is available under [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0.txt)

# Configuration
The exporter needs to be configured to extract metrics for one or more AWS Service types. AWS Service types map to a
namespace in CloudWatch.

<details>
  <summary>Supported CloudWatch namespaces</summary>

* AWS/ApplicationELB
* AWS/ApiGateway
* AWS/AppSync
* AWS/Billing
* AWS/CloudFront
* AWS/DocDB
* AWS/DynamoDB
* AWS/EBS
* AWS/Elasticache
* AWS/EC2
* AWS/EC2Spot
* AWS/ECS
* ECS/ContainerInsights
* AWS/EFS
* AWS/ELB
* AWS/ElasticMapReduce
* AWS/ES
* AWS/FSx
* AWS/GameLift
* Glue
* AWS/Kinesis
* AWS/NetworkFirewall
* AWS/NATGateway
* AWS/Lambda
* LambdaInsights
* AWS/NetworkELB
* AWS/Redshift
* AWS/RDS
* AWS/Route53Resolver
* AWS/S3
* AWS/SES
* AWS/SQS
* AWS/TransitGateway
* AWS/VPN
* AWS/AutoScaling
* AWS/Kafka
* AWS/Firehose
* AWS/SNS
* AWS/States
* AWS/WAFV2
</details>  

**Scrape Interval**

The exporter scrapes metrics periodically at a frequency determined by the **scrapeInterval**. In each scrape the
exporter requests an aggregate statistic for a specific **period**. The unit for time interval configuration is
`second`. The default scrape interval is `60` seconds. The default period is `60` seconds. The time intervals can be
configured at in the following different ways

* **Global** This will apply to all metrics across all namespaces
* **Namespace** This will apply to all metrics for a specific namespace
* **Metric** This will apply to a specific metric in a specific namespace

<details>
  <summary>ECS Tasks Service Discovery</summary>

The exporter can discover ECS Tasks and generate scrape targets. The target port and metric scrape url path will be automatically detected from the docker labels
`PROMETHEUS_EXPORTER_PORT` and `PROMETHEUS_EXPORTER_PATH`. If the `PROMETHEUS_EXPORTER_PATH` is not specified it will be defaulted to `/metrics`. If the `PROMETHEUS_EXPORTER_PORT` is not specified, and the task has only one container which exposes only one port, this port will be used. If the container has multiple ports exposed or if there are multiple containers in the task then task target ports need to be specified in the scrape configuration yaml as follows

```
- ecsTaskScrapeConfigs
  - containerDefinitionName: model-builder
    containerPort: 8080
    metricPath: /model-builder/actuator/prometheus
```
The `metricPath` is still optional. If not specified the default value of `/metrics` will be used
</details>


<details>
  <summary>Exporter internal metrics</summary>
The exporter also exports the following metrics to enable monitoring itself

|Metric Name|Description|
|---|---|
|aws_exporter_milliseconds_sum| AWS API Latency Counter |
|aws_exporter_milliseconds_count| AWS API Count |
|aws_exporter_interval_seconds|The scrape interval metric for each namespace|
|aws_exporter_period_seconds| The statistic period for each namespace|
</details>

<details>
  <summary>Running it locally</summary>
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
  </details>

<details>
  <summary>AWS IAM Permissions</summary>
The following IAM permissions need to be configured for the exporter
```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "apigateway:GET", 
                "autoscaling:DescribeAutoScalingGroups", 
                "autoscaling:DescribeLoadBalancerTargetGroups", 
                "autoscaling:DescribeLoadBalancers", 
                "autoscaling:DescribeScalingActivities", 
                "autoscaling:DescribeTags", 
                "cloudwatch:DescribeAlarms", 
                "cloudwatch:GetMetricData", 
                "cloudwatch:ListMetrics", 
                "config:Describe*", 
                "config:Get*", 
                "config:List*", 
                "dynamodb:ListTables", 
                "ec2:DescribeVolumes", 
                "ecs:DescribeContainerInstances", 
                "ecs:DescribeServices", 
                "ecs:DescribeTaskDefinition", 
                "ecs:DescribeTasks", 
                "ecs:ListAccountSettings", 
                "ecs:ListClusters", 
                "ecs:ListContainerInstances", 
                "ecs:ListServices", 
                "ecs:ListTaskDefinitionFamilies", 
                "ecs:ListTaskDefinitions", 
                "ecs:ListTasks", 
                "elasticloadbalancing:DescribeListeners", 
                "elasticloadbalancing:DescribeLoadBalancers", 
                "elasticloadbalancing:DescribeRules", 
                "elasticloadbalancing:DescribeTags", 
                "elasticloadbalancing:DescribeTargetGroups", 
                "elasticloadbalancing:DescribeTargetHealth", 
                "firehose:DescribeDeliveryStream", 
                "firehose:ListDeliveryStreams", 
                "firehose:ListTagsForDeliveryStream", 
                "kinesis:DescribeStream", 
                "kinesis:DescribeStreamConsumer", 
                "kinesis:DescribeStreamSummary", 
                "kinesis:ListStreamConsumers", 
                "kinesis:ListStreams", 
                "kinesis:ListTagsForStream", 
                "kinesisanalytics:DescribeApplication", 
                "kinesisanalytics:ListApplications", 
                "kinesisanalytics:ListTagsForResource", 
                "lambda:GetAccountSettings", 
                "lambda:GetFunctionConcurrency", 
                "lambda:GetProvisionedConcurrencyConfig", 
                "lambda:ListAliases", 
                "lambda:ListEventSourceMappings", 
                "lambda:ListFunctionEventInvokeConfigs", 
                "lambda:ListFunctions", 
                "lambda:ListProvisionedConcurrencyConfigs", 
                "lambda:ListVersionsByFunction", 
                "logs:FilterLogEvents", 
                "redshift:DescribeClusters", 
                "redshift:ListDatabases", 
                "redshift:ListTables", 
                "rds:DescribeDBInstances", 
                "rds:DescribeDBClusters", 
                "s3:GetObject", 
                "s3:ListBucket", 
                "s3:ListAllMyBuckets", 
                "sns:ListTopics", 
                "sqs:ListQueues", 
                "tag:GetResources", 
            ],
            "Resource": "*"
        }
    ]
}
```
  </details>
