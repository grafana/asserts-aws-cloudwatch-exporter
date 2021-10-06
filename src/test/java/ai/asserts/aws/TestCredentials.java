/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.util.Optional;

@Component
public class TestCredentials {
    public Optional<AwsSessionCredentials> getSessionCredentials() {
        Credentials credentials = Credentials.builder()
                .accessKeyId("ASIAU7XAO4EF7R7Z3BHH")
                .secretAccessKey("00W1mINvYf81Ui5vQzb884XirWYtBEtl4J9QtZE+")
                .sessionToken("FwoGZXIvYXdzEO7//////////wEaDDey72tuUJ/XkB8+1SK4AdrBvpT3mEQxtBB3U9zHoHtr/t0npzB+6Z85sZsiAJJ6gA1wDI0j6DY5L0W9YWawKwWuV6M7brRExFZ9lEPHfGub2hbQdQ5pYDxS2KjnouAMlwvCje0bOWY6KKLi3lRbpUJrdYzawfvWE/c8GFaBJM4w7Q1Ga0m9h34S2U6taWevcMEodH37cp5bNMv1kUR2xWvPnfunNJzK+EGe4MBIU1Qyzsdvn7S3lUYCSnL1Tz2z4PBVz/IUxIoo4bLvigYyLeQR0/sHyhD5Br4ZF5d0VtS8PJcxtkD91pzidaryQIdNfejEtIbZcEzA073Crg==")
                .build();

        return Optional.of(AwsSessionCredentials.create(
                credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()));
//        return Optional.empty();
    }
}
