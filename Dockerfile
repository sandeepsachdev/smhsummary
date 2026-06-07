# Multi-stage build: Maven → Spring Boot fat jar → slim runtime image.

# --- Build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Resolve deps first so they cache between rebuilds.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

# --- Runtime ---
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/smh-summary.jar /app/app.jar

ENV JAVA_OPTS=""
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
