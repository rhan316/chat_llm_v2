FROM docker.io/library/eclipse-temurin:25-jre

WORKDIR /app

COPY target/Spring_AI-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8081

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
