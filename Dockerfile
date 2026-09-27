# Imagem da versão de demonstração (ver DEPLOY-DEMO.md). Dois estágios: compila com Maven e roda
# só o JAR num JRE enxuto.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/divinaluz-*.jar app.jar
# Plano gratuito tem ~512 MB: limita o heap para a JVM não ser encerrada por falta de memória.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
ENV SPRING_PROFILES_ACTIVE=demo
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
