/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.asserts.aws.ResourceType.DynamoDBTable;
import static ai.asserts.aws.ResourceType.LambdaFunction;
import static ai.asserts.aws.ResourceType.SQSQueue;

@Component
public class ResourceMapper {
    private static final Pattern SQS_QUEUE_ARN_PATTERN = Pattern.compile("arn:aws:sqs:.*?:.*?:(.+)");
    private static final Pattern DYNAMODB_TABLE_ARN_PATTERN = Pattern.compile("arn:aws:dynamodb:.*?:.*?:table/(.+?)(/.+)?");
    private static final Pattern LAMBDA_ARN_PATTERN = Pattern.compile("arn:aws:lambda:.*?:.*?:function:(.+?)(:.+)?");

    private final List<Mapper> mappers = new ImmutableList.Builder<Mapper>()
            .add(arn -> {
                if (arn.contains("sqs")) {
                    Matcher matcher = SQS_QUEUE_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(new Resource(SQSQueue, arn, matcher.group(1)));
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains("dynamodb") && arn.contains("table")) {
                    Matcher matcher = DYNAMODB_TABLE_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(new Resource(DynamoDBTable, arn, matcher.group(1)));
                    }
                }
                return Optional.empty();
            })
            .add(arn -> {
                if (arn.contains("lambda") && arn.contains("function")) {
                    Matcher matcher = LAMBDA_ARN_PATTERN.matcher(arn);
                    if (matcher.matches()) {
                        return Optional.of(new Resource(LambdaFunction, arn, matcher.group(1)));
                    }
                }
                return Optional.empty();
            })
            .build();

    public Optional<Resource> map(String arn) {
        return mappers.stream()
                .map(mapper -> mapper.get(arn))
                .filter(Optional::isPresent)
                .findFirst().orElse(Optional.empty());
    }

    public interface Mapper {
        Optional<Resource> get(String arn);
    }
}

