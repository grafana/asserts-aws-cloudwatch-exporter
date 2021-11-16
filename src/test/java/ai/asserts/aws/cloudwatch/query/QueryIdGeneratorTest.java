
package ai.asserts.aws.cloudwatch.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryIdGeneratorTest {
    @Test
    void next() {
        QueryIdGenerator queryIdGenerator = new QueryIdGenerator();
        assertEquals("q_1", queryIdGenerator.next());
        assertEquals("q_2", queryIdGenerator.next());
    }
}
