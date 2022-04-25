
package ai.asserts.aws;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.util.Optional;

@Component
public class TestCredentials {
    public Optional<AwsSessionCredentials> getSessionCredentials() {
        Credentials credentials = Credentials.builder()
                .accessKeyId("ASIAU7XAO4EFZGJBEFMD")
                .secretAccessKey("loa/pKZ0NCKb8NURmUbhLNh4471ip4hy5xmJzHDU")
                .sessionToken("FwoGZXIvYXdzEJX//////////wEaDB2KCtC0Mf1GiIgVWyK4Af2Hl79yECmqV9zHbeB2B2anuUnuWdq4+VOJtRMSvHT3HcaOwwmQbPFuRyuKqciPIcRVUo7aO3KkxAJ7jqqanNH1502OlEqOp8NJNjNQU3nNy5h2FNp9daEsHZDPVjhaMQUN9wv7GvyXlthHeH0nywUYBLub9HwlCfA06ShaihURkldpDmhhYaN1SATCVsgICaKWEFQwTcwQwVvoiUjOLL6WTfBwjAFQYI/+phNTOLFGiC1TNYbD2s4og82IkwYyLXiiArn/hGQa+YVdNyOuWszMEFRdUWMEEjIn5wkLmdJ8r2FYDFfq08Y+XyEHkQ==")
                .build();

        return Optional.of(AwsSessionCredentials.create(
                credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()));
//        return Optional.empty();
    }
}
