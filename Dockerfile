FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache git curl postgresql-client kubectl
WORKDIR /app
COPY --from=build /workspace/target/replayfix-platform-*.jar /app/replayfix.jar
COPY config /app/config
COPY replay-packages /app/replay-packages
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/replayfix.jar"]
