# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Cache dependency downloads separately from source — speeds up rebuilds
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar

# Routes file mounted as a volume at runtime (see docker-compose.yml)
# Falls back to the classpath routes.properties baked into the JAR
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
