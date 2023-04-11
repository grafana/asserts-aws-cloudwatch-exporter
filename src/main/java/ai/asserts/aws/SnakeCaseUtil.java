/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import org.springframework.stereotype.Component;

@Component
public class SnakeCaseUtil {
    public static final String UNDERSCORE = "_";

    public String toSnakeCase(String input) {
        StringBuilder builder = new StringBuilder();
        boolean lastCaseWasSmall = false;
        int numContiguousUpperCase = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '-' || c == ':' || c == '/' || c == '.') {
                appendUnderscore(builder);
                numContiguousUpperCase = 0;
                continue;
            } else if (Character.isUpperCase(c) && lastCaseWasSmall) {
                appendUnderscore(builder);
            } else if (Character.isLowerCase(c) && numContiguousUpperCase > 1) {
                char lastUpperCaseLetter = builder.toString().charAt(builder.length() - 1);
                builder.deleteCharAt(builder.length() - 1);
                appendUnderscore(builder);
                builder.append(lastUpperCaseLetter);
            }
            builder.append(c);
            lastCaseWasSmall = Character.isLowerCase(c);
            if (Character.isUpperCase(c)) {
                numContiguousUpperCase++;
            } else {
                numContiguousUpperCase = 0;
            }
        }
        return builder.toString().toLowerCase();
    }

    private void appendUnderscore(StringBuilder builder) {
        if (builder.charAt(builder.length() - 1) != '_')
            builder.append(UNDERSCORE);
    }
}
