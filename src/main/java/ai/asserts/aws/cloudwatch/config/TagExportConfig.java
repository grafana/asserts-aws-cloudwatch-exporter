/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;

import java.util.regex.Pattern;

@Getter
@Setter
@EqualsAndHashCode(of = {"excludePattern", "includePattern"})
@NoArgsConstructor
public class TagExportConfig {
    private String excludePattern;
    private String includePattern;
    private Pattern exclude;
    private Pattern include;

    public void compile() {
        if (excludePattern != null) {
            exclude = Pattern.compile(excludePattern);
        }
        if (includePattern != null) {
            include = Pattern.compile(includePattern);
        }
    }

    public boolean shouldCaptureTag(Tag tag) {
        boolean _include = true;
        boolean _exclude = false;

        if (include != null) {
            _include = include.matcher(tag.key()).matches();
        }
        if (exclude != null) {
            _exclude = exclude.matcher(tag.key()).matches();
        }

        return _include && !_exclude;
    }
}
