# Stage 1 - Build
FROM gradle:7.6.1-jdk8 as builder
RUN gradle --version && java -version
RUN apt-get install git && git --version

WORKDIR /home/gradle/app
# Only copy gradle dependency-related files
COPY --chown=gradle:gradle build.gradle /home/gradle/app/
COPY --chown=gradle:gradle settings.gradle /home/gradle/app/
COPY --chown=gradle:gradle gradle.properties /home/gradle/app/

# Trigger Build with only gradle files to download proj gradle dependencies
# This build is expected to fail since there is obviously no src code at this point
# We'll just route the output to a black hole and swallow the error
# Purpose: This layer will be cached so after 1st build we can pick up from here with
# all of our gradle dependencies already downloaded
# Adds ~7 secs to 1st build, (~1min), subsequent builds will be ~20 secs
# Without this 1-liner every container build would be ~1min
COPY --chown=gradle:gradle ./.git /home/gradle/app/.git
COPY --chown=gradle:gradle ./src /home/gradle/app/src
COPY --chown=gradle:gradle ./config /home/gradle/app/config
RUN gradle build --no-daemon > /dev/null 2>&1 || true
RUN gradle bootJar --no-daemon --stacktrace


# Stage 2 - Create a size optimized Image for our Service with only what we need to run
# FIXME
# Using the latest base Amazon Linux image from here https://hub.docker.com/_/amazonlinux/tags?page=1 and adding JDK
# doesn't resolve the vulnerability. For e.g, the snyk vulnerabiliby and fix recommendation page
# https://security.snyk.io/vuln/SNYK-AMZN2-GLIBC-3058942 describes the fix. But the amazon linux base image doesn't
# seem to incorporate this fix. Updating the libraries resolves the issue but we will end up accumulating update
# commands over time. Need a better solution
FROM amazoncorretto:8-al2-jdk

EXPOSE 8010
WORKDIR /opt/demo_app
COPY --from=builder /home/gradle/app/src/dist/conf/cloudwatch_scrape_config_sample.yml ./cloudwatch_scrape_config.yml
COPY --from=builder /home/gradle/app/src/dist/conf/application.properties ./application.properties
COPY --from=builder /home/gradle/app/build/libs/* ./
COPY --from=builder /home/gradle/app/build/resources/main/*.xml ./
COPY --from=builder /home/gradle/app/build/resources/main/*.properties ./
# COPY jmx_prometheus_javaagent-0.16.1.jar ./
# COPY httpserver_config.yml ./
CMD ["/bin/sh", "-c", "java -Daws_exporter.deployment_mode=$DEPLOYMENT_MODE -Daws_exporter.tenant_mode=single -Dhekate.enable=$HEKATE_ENABLE -Dhekate.cluster.seed.cloudstore.enable=$HEKATE_CLOUDSTORE_ENABLE -Dhekate.cluster.seed.cloudstore.provider=$HEKATE_CLOUDSTORE_PROVIDER -Dhekate.cluster.seed.cloudstore.container=$HEKATE_CLOUDSTORE_CONTAINER -Dhekate.cluster.namespace=aws-exporter -jar app-*.jar --spring.config.location=application.properties"]
# CMD ["/bin/sh", "-c", "java -javaagent:./jmx_prometheus_javaagent-0.16.1.jar=8095:httpserver_config.yml -jar app-*.jar --spring.config.location=application.properties"]

LABEL "PROMETHEUS_EXPORTER_PORT"="8010"
LABEL "PROMETHEUS_EXPORTER_PATH"="/aws-exporter/actuator/prometheus"
