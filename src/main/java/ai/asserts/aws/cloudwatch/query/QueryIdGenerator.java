
package ai.asserts.aws.cloudwatch.query;

import org.springframework.stereotype.Component;

@Component
public class QueryIdGenerator {
    private int next = 1;

    public String next() {
        return "q_" + (next++);
    }
}
