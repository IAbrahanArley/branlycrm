FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# The secrets directory is copied into the empty tenant_secrets volume with this ownership.
RUN groupadd --system app && useradd --system --gid app app \
    && mkdir -p /run/branly/tenant-secrets \
    && chown app:app /run/branly/tenant-secrets && chmod 700 /run/branly/tenant-secrets
COPY --from=build --chown=app:app /app/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]