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
# Plano gratuito do Render: ~512 MB e só 0,1 CPU. Heap limitado, GC serial e só o compilador C1
# (TieredStopAtLevel=1), que aquece bem mais rápido com pouca CPU.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"
ENV SPRING_PROFILES_ACTIVE=demo
# CDS (Class Data Sharing): uma execução de treino no build grava as classes carregadas em app.jsa,
# e a subida real lê dali. Com 0,1 CPU a subida cai de ~4 min para ~1 min (medido em 2026-09-27),
# o que vale também a cada vez que a demo "acorda". O treino para logo após montar o contexto
# (spring.context.exit=onRefresh) e não precisa de banco: Flyway e o acesso a metadados do
# Hibernate ficam desligados só nesta execução.
RUN java -Djarmode=tools -jar app.jar extract --destination aplicacao && rm app.jar
RUN java -XX:ArchiveClassesAtExit=aplicacao/app.jsa -Dspring.context.exit=onRefresh \
        -Dspring.main.lazy-initialization=false \
        -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
        -jar aplicacao/app.jar
EXPOSE 8080
CMD ["java", "-XX:SharedArchiveFile=aplicacao/app.jsa", "-jar", "aplicacao/app.jar"]
