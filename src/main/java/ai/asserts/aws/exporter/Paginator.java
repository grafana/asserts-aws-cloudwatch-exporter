/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import lombok.Getter;

@Getter
public class Paginator {
    private String lastToken;
    private String nextToken;

    public void nextToken(String newNextToken) {
        lastToken = nextToken;
        nextToken = newNextToken;
    }

    public boolean hasNext() {
        return nextToken != null && !nextToken.equalsIgnoreCase(lastToken);
    }
}
