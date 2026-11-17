FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 boxbox \
    && useradd --uid 10001 --gid boxbox --no-create-home boxbox \
    && install -d -o 10001 -g 10001 /var/lib/boxbox/media/uploads

WORKDIR /app
COPY --chown=boxbox:boxbox build/docker/app.jar /app/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent --max-time 4 http://127.0.0.1:8080/api/boards > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
