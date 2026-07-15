FROM gradle:9.2.1-jdk21 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon clean installDist

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install --no-install-recommends -y curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /home/gradle/project/build/install/ai-review/ ./
RUN mkdir -p /app/data/indexes && chown -R app:app /app
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 CMD ["curl", "--fail", "--silent", "http://127.0.0.1:8080/health"]
ENTRYPOINT ["/app/bin/ai-review"]
