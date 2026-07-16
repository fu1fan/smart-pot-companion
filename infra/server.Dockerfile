FROM dockerproxy.net/library/gradle:9.4.1-jdk21 AS build
WORKDIR /workspace
COPY kotlin/ ./
RUN gradle --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m :server:installDist

FROM dockerproxy.net/library/eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/server/build/install/server/ ./
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
