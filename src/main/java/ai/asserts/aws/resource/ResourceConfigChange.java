/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.resource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@EqualsAndHashCode
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceConfigChange {
    private String version;
    private String id;
    private String source;
    private String account;
    private String time;
    private String region;
    private List<String> resources;
    private ResourceConfigChangeDetail detail;
}
