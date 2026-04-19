# ── Stage 1: Build ────────────────────────────────────────────────────────
# Uses Maven wrapper so no local Maven installation is required.
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# Copy dependency manifest first so Docker layer caching skips the
# dependency download step when only source files change.
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Now copy source and build
COPY src/ src/
RUN ./mvnw package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────
# JRE-only image is ~150 MB smaller than the JDK image.
FROM eclipse-temurin:17-jre

WORKDIR /app

# Non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup appuser
USER appuser

COPY --from=builder /app/target/distributask-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
