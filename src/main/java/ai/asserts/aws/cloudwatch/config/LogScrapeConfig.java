/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

@EqualsAndHashCode
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Slf4j
public class LogScrapeConfig {
    private String lambdaFunctionName;
    @EqualsAndHashCode.Exclude
    private Pattern functionNamePattern;
    private String logFilterPattern;
    private String regexPattern;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Pattern pattern;
    private Map<String, String> labels;
    @EqualsAndHashCode.Exclude
    private String sampleLogMessage;
    @EqualsAndHashCode.Exclude
    private Map<String, String> sampleExpectedLabels;
    @Builder.Default
    private boolean valid = false;

    /**
     * Compiles the {@link #regexPattern} and stores the result in {@link #pattern}. Optionally, if a
     * {@link #sampleLogMessage} and {@link #sampleExpectedLabels} are specified, will extract the map of labels from
     * the {@link #labels} and the {@link #sampleLogMessage} using the {@link #regexPattern} and check if the extracted
     * labels match the expected labels. If they don't match, this config will be marked as invalid and will not be
     * used for log scraping.
     */
    public void initalize() {
        compile();
        valid = true;

        if (StringUtils.hasText(sampleLogMessage) && !CollectionUtils.isEmpty(sampleExpectedLabels)) {
            Map<String, String> extractedLabels = extractLabels(sampleLogMessage);
            if (!extractedLabels.equals(sampleExpectedLabels)) {
                log.error("Pattern {} on message \n{}\n resulted in labels \n{}\n but did not match expected labels " +
                                "\n{}\n. This log scrape config will not be used to scrape metrics from logs",
                        regexPattern, sampleLogMessage, extractedLabels, sampleExpectedLabels);
                valid = false;
            }
        }
    }

    @VisibleForTesting
    public void compile() {
        functionNamePattern = Pattern.compile(lambdaFunctionName);
        pattern = Pattern.compile(regexPattern);
    }

    public boolean shouldScrapeLogsFor(String functionName) {
        return functionNamePattern.matcher(functionName).matches();
    }

    /**
     * Extract labels while converting a Cloud Log message into a prometheus metric. For e.g. if the log message looks
     * like
     * <pre>
     *     Published OrderRequest to queue queue1
     * </pre>
     * <p>
     * and the {@link #regexPattern} is specified as
     * <pre>
     *     Published (.+?) to queue (.+?)
     * </pre>
     * and the {@link #labels} is specified as
     * <pre>
     *     {
     *         "destination_type": "SQSQueue",
     *         "destination_name": "$2",
     *         "message_type": "$1"
     *     }
     * </pre>
     * Then this method will return a map as follows
     * <pre>
     *     {
     *         "destination_type": "SQSQueue",
     *         "destination_name": "queue1",
     *         "message_type": "OrderRequest"
     *     }
     * </pre>
     *
     * @param message The log message
     * @return A map of labels.
     */
    public Map<String, String> extractLabels(String message) {
        Matcher matcher = pattern.matcher(message.trim());
        if (matcher.matches()) {
            Map<String, String> extractedLabels = new TreeMap<>();
            Map<String, String> matchedBindings = new TreeMap<>();
            for (int i = 0; i <= matcher.groupCount(); i++) {
                matchedBindings.put(format("$%d", i), matcher.group(i));
            }
            labels.forEach((name, valueExpr) ->
                    extractedLabels.put(format("d_%s", name), resolveBindings(valueExpr, matchedBindings)));
            return extractedLabels;
        } else {
            return Collections.emptyMap();
        }
    }

    /**
     * Resolves a label value expression which may refer to capturing groups from {@link #regexPattern} from the result
     * of matching the pattern with a cloud watch log message
     *
     * @param inputString The input string
     * @param values      Map of variable values. Will have keys like <code>$1, $2 etc</code> with the values matching
     *                    the regexp capturing groups as a result of matching a cloudwatch log message with
     *                    {@link #regexPattern}
     * @return A string with capturing group references substituted with their values
     */
    private String resolveBindings(String inputString, Map<String, String> values) {
        String outputString = inputString;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            outputString = outputString.replace(entry.getKey(), entry.getValue());
        }
        return outputString;
    }
}
