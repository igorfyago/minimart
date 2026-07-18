# minimart + minipay as one shippable image. Multi-stage: Maven builds, a bare
# JRE runs. No framework means no fat-jar magic: compiled classes plus the
# dependency jars on a classpath is the whole artifact.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package dependency:copy-dependencies

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/classes ./classes
COPY --from=build /app/target/dependency ./deps
EXPOSE 8081 8082
# every address comes from the environment · the same image runs on a laptop,
# in compose, or on a cluster. Config is location, not code.
ENTRYPOINT ["java", "-cp", "classes:deps/*", "dev.minimart.Main"]
