# Build from the repository root (the library is built first):
#   docker build -f basic-rest/Dockerfile -t basic-rest:0.0.1 .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY secret-manager ./secret-manager
RUN mvn -q -B -f secret-manager/pom.xml install -DskipTests
COPY basic-rest/pom.xml ./basic-rest/pom.xml
RUN mvn -q -B -f basic-rest/pom.xml dependency:go-offline
COPY basic-rest/src ./basic-rest/src
RUN mvn -q -B -f basic-rest/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 app
COPY --from=build /build/basic-rest/target/basic-rest-*.jar app.jar
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
