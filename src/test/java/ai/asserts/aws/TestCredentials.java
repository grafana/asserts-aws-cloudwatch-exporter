
package ai.asserts.aws;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.util.Optional;

@Component
public class TestCredentials {
    public Optional<AwsSessionCredentials> getSessionCredentials() {
        Credentials credentials = Credentials.builder()
                .accessKeyId("ASIAU7XAO4EF5R73QZ63")
                .secretAccessKey("mRiek8/jZoLVdbl46Y4GPDDgDehRd6muXM6ZoXn5")
                .sessionToken("FwoGZXIvYXdzEMj//////////wEaDMtfXEnOmj1lKgUKwiK4AX58l6zz/VeaD7Vl51TdmlPly5e+Fvfa55bTiGV4SqqZBHIWqNJv/r/zo7CS90UZBypU0EfXjIKSRzoYDd1ulidJyhQlcJhqZA96gkeCma0eWdk+/Eglz98XqVrC+wNDWc8GE4ZdItwaMd9mShRf2yE/l/SvPaiPloNVOmfzhQ9/LTazJEZ6Cq8V+1+Gxh7R91vBoBo0+KUA6FRswCjamPEoT3hseO6X5OR2AZp4A/KUyB8TXMAr4JAoh/PHjAYyLXj4U/JI6qvRpju16QJew0IwL6MarielZMVCKjZ1s5E9Pq+g+gRfAC8VjJYzgw==")
                .build();

        return Optional.of(AwsSessionCredentials.create(
                credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()));
//        return Optional.empty();
    }
}
