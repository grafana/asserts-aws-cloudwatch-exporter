/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PaginatorTest {
    @Test
    void testHasNext() {
        Paginator paginator = new Paginator();
        assertFalse(paginator.hasNext());

        paginator.nextToken("token1");
        assertTrue(paginator.hasNext());

        paginator.nextToken("token2");
        assertTrue(paginator.hasNext());

        paginator.nextToken("token2");
        assertFalse(paginator.hasNext());
    }
}
