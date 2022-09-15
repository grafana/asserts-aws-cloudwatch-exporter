/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PatternMatcherTest {
    @Test
    public void match_NOT_PRESENT() {
        PatternMatcher patternMatcher = new PatternMatcher("NOT_PRESENT");
        assertTrue(patternMatcher.matches(null));
        assertFalse(patternMatcher.matches(""));
        assertFalse(patternMatcher.matches("value"));
    }

    @Test
    public void matchPattern() {
        PatternMatcher patternMatcher = new PatternMatcher("ab+");
        assertTrue(patternMatcher.matches("ab"));
        assertTrue(patternMatcher.matches("abb"));
        assertFalse(patternMatcher.matches("a"));
        assertFalse(patternMatcher.matches("abc"));
        assertFalse(patternMatcher.matches(""));
        assertFalse(patternMatcher.matches(null));
    }

    @Test
    public void negationMatch() {
        PatternMatcher patternMatcher = new PatternMatcher("!ab+");
        assertFalse(patternMatcher.matches("ab"));
        assertFalse(patternMatcher.matches("abb"));
        assertTrue(patternMatcher.matches("a"));
        assertTrue(patternMatcher.matches("abc"));
        assertTrue(patternMatcher.matches(""));
        assertFalse(patternMatcher.matches(null));
    }
}
