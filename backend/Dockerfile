# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy AS build

ARG MODULE
WORKDIR /workspace/backend

COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
COPY backend/api-gateway/pom.xml api-gateway/pom.xml
COPY backend/auth-service/pom.xml auth-service/pom.xml
COPY backend/campaign-service/pom.xml campaign-service/pom.xml
COPY backend/notification-service/pom.xml notification-service/pom.xml
COPY backend/reporting-service/pom.xml reporting-service/pom.xml

RUN test -n "$MODULE" \
    && chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress -pl "$MODULE" -am dependency:go-offline

COPY backend .

RUN ./mvnw --batch-mode --no-transfer-progress -pl "$MODULE" -am -DskipTests package \
    && cp "$MODULE"/target/"$MODULE"-*.jar /tmp/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --system --gid 1001 notifyhub \
    && useradd --system --uid 1001 --gid notifyhub --home-dir /app --shell /usr/sbin/nologin notifyhub

WORKDIR /app
COPY --from=build --chown=notifyhub:notifyhub /tmp/app.jar app.jar

USER 1001:1001

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
