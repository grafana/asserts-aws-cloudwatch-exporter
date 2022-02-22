/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */

package ai.asserts.aws.cloudwatch.alarms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.With;

import java.util.List;
import java.util.SortedMap;

@Getter
@Setter
@ToString
@Builder
@With
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrometheusAlerts {

    private String version;
    private String groupKey;
    private PrometheusAlertStatus status;
    private String receiver;
    private SortedMap<String, String> groupLabels;
    private SortedMap<String, String> commonLabels;
    private String externalURL;
    private List<PrometheusAlert> alerts;
}
