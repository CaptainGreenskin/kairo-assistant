FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY kairo-assistant-core/pom.xml kairo-assistant-core/
COPY kairo-assistant-cli/pom.xml kairo-assistant-cli/
COPY kairo-assistant-server/pom.xml kairo-assistant-server/
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B -q 2>/dev/null || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -pl kairo-assistant-server -am -B -q

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S kairo && adduser -S kairo -G kairo
WORKDIR /app
COPY --from=build /app/kairo-assistant-server/target/*.jar app.jar
RUN chown -R kairo:kairo /app
USER kairo
EXPOSE 8080
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
