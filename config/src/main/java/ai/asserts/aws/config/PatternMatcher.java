/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.regex.Pattern;

import static org.springframework.util.StringUtils.hasLength;

/**
 * Specialised pattern matcher that does a regular expression pattern match by default. But in addition also allows for
 * negation match of a regular expression and matching for absence of value
 */
@EqualsAndHashCode
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
public class PatternMatcher {
    private String pattern;
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Pattern compiledPattern;
    private boolean negateMatch;
    private boolean assertForAbsence;

    public PatternMatcher(String pattern) {
        if (!hasLength(pattern)) {
            throw new RuntimeException("Invalid pattern [" + pattern + "]");
        }

        if ("NOT_PRESENT".equals(pattern)) {
            assertForAbsence = true;
        } else if ('!' == pattern.charAt(0)) {
            this.pattern = pattern;
            this.compiledPattern = Pattern.compile(pattern.substring(1));
            this.negateMatch = true;
        } else {
            this.pattern = pattern;
            this.compiledPattern = Pattern.compile(pattern);
        }
    }

    public boolean matches(String value) {
        if (assertForAbsence) {
            return value == null;
        } else if (value != null) {
            boolean matches = compiledPattern.matcher(value).matches();
            return (negateMatch && !matches) || (!negateMatch && matches);
        }
        return false;
    }
}
