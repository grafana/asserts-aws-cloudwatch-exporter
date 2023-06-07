
package ai.asserts.aws.lambda;

import ai.asserts.aws.resource.Resource;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Getter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@SuperBuilder
public class LambdaFunction {
    @EqualsAndHashCode.Include
    private final String name;
    @EqualsAndHashCode.Include
    private final String region;
    @EqualsAndHashCode.Include
    private final String account;
    private final String tenant;
    private final String arn;
    private final Resource resource;
    private final Integer memoryMB;
    private final Integer timeoutSeconds;
}
