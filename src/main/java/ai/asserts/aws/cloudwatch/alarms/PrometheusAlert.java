/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */

package ai.asserts.aws.cloudwatch.alarms;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.With;

import java.time.ZonedDateTime;
import java.util.SortedMap;

@Getter
@Setter
@ToString
@With
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrometheusAlert {

    private PrometheusAlertStatus status;
    private SortedMap<String, String> labels;
    private SortedMap<String, String> annotations;
    private ZonedDateTime startsAt;
    private ZonedDateTime endsAt;
    private String generatorURL;

    @JsonIgnore
    public String getName() {
        return (labels != null) ? labels.get("alertname") : null;
    }
}
