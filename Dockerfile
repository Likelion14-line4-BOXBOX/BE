FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 boxbox \
    && useradd --uid 10001 --gid boxbox --no-create-home boxbox

# Verify the RDS server identity with the current AWS CA bundle.
RUN curl --fail --silent --show-error --retry 3 \
      https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o /tmp/rds.pem \
    && awk '/BEGIN CERTIFICATE/{n++} {print > ("/tmp/rds-ca-" n ".pem")}' /tmp/rds.pem \
    && for cert in /tmp/rds-ca-*.pem; do \
         keytool -importcert -noprompt -trustcacerts -cacerts -storepass changeit \
           -alias "$(basename "$cert")" -file "$cert"; \
       done \
    && rm -f /tmp/rds.pem /tmp/rds-ca-*.pem

WORKDIR /app
COPY --chown=boxbox:boxbox build/docker/app.jar /app/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent --max-time 4 http://127.0.0.1:8080/api/boards > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
