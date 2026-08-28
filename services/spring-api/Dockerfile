FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace/services/spring-api
COPY services/spring-api/gradlew services/spring-api/gradlew.bat services/spring-api/build.gradle services/spring-api/settings.gradle ./
COPY services/spring-api/gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies
COPY services/spring-api/src src
COPY docs/contracts /workspace/docs/contracts
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build --chown=spring:spring /workspace/services/spring-api/build/libs/*.jar app.jar
USER spring:spring
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
