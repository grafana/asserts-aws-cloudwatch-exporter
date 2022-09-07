
package ai.asserts.aws.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogScrapeConfig {
    private String lambdaFunctionName;
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private Pattern functionNamePattern;
    private List<String> functionNames;
    private String logFilterPattern;
    private String regexPattern;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
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
    public void initialize() {
        valid = compile() || !CollectionUtils.isEmpty(functionNames);

        if (valid && StringUtils.hasText(sampleLogMessage) && !CollectionUtils.isEmpty(sampleExpectedLabels)) {
            Map<String, String> extractedLabels = extractLabels(sampleLogMessage);
            if (!extractedLabels.equals(sampleExpectedLabels)) {
                valid = false;
            }
        }
    }

    private boolean compile() {
        if (regexPattern != null) {
            functionNamePattern = Pattern.compile(lambdaFunctionName);
            pattern = Pattern.compile(regexPattern);
            return true;
        }
        return false;
    }

    public boolean shouldScrapeLogsFor(String functionName) {
        return valid && (functionNamePattern != null && functionNamePattern.matcher(functionName).matches())
                || (functionNames != null && functionNames.contains(functionName));
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
