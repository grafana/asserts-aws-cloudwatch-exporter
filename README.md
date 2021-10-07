# aws-exporter
Standalone exporter to export AWS CloudWatch Metrics and Logs as prometheus metrics. This exporter uses AWS APIs and fetches both meta data about some AWS Resources and metric data. The following IAM permissions are needed for this exporter

```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "lambda:ListFunctionEventInvokeConfigs",
                "lambda:ListVersionsByFunction",
                "lambda:ListAliases",
                "logs:FilterLogEvents"
            ],
            "Resource": [
                "arn:aws:logs:*:342994379019:log-group:/aws/lambda/*",
                "arn:aws:lambda:*:342994379019:function:*"
            ]
        },
        {
            "Effect": "Allow",
            "Action": [
                "tag:GetResources",
                "lambda:ListFunctions",
                "cloudwatch:GetMetricData",
                "lambda:ListEventSourceMappings",
                "cloudwatch:ListMetrics"
            ],
            "Resource": "*"
        }
    ]
}
```
