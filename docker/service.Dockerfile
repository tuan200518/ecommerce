# syntax=docker/dockerfile:1.7
# Dockerfile dùng chung cho mọi microservice.
# Stage build KHÔNG phụ thuộc ARG SERVICE → BuildKit build reactor Maven đúng 1 lần rồi dùng lại cho cả 10 image.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY libs libs
COPY services services
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app
RUN useradd --system --uid 1001 app \
    && mkdir -p /tmp/kafka-streams && chown app /tmp/kafka-streams
COPY --from=build /workspace/services/${SERVICE}/target/app.jar app.jar
USER app
ENV JAVA_OPTS="-Xms64m -Xmx320m -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
