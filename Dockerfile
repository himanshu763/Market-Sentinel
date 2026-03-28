# ─────────────────────────────────────────────
#  Stage 1 — Build
#  Uses full JDK + Maven wrapper to compile and
#  package the fat JAR.  This stage is thrown
#  away — its output is the only thing that
#  survives into the final image.
# ─────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy the Maven wrapper and pom first so Docker
# can cache the dependency-download layer.  As
# long as pom.xml doesn't change, `mvn dependency:go-offline`
# won't re-run even when your source code changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Now copy source and build the fat JAR
COPY src ./src
RUN ./mvnw package -DskipTests -q


# ─────────────────────────────────────────────
#  Stage 2 — Run
#  Uses a slim JRE-only image (~85 MB vs ~400 MB
#  for a full JDK image).  Only the JAR from the
#  build stage is copied over — no Maven, no src.
# ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy only the built artifact
COPY --from=builder /app/target/market-sentinel-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
