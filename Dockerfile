# Imagem da aplicação em dois estágios: compila com Maven e roda só o JAR num JRE enxuto. Nasceu
# para a demo na nuvem (descontinuada em 2026-09-30) e ficou porque serve a qualquer deploy em
# contêiner; o banco vem de DATABASE_URL ou de SPRING_DATASOURCE_* (ver DatabaseUrlConfig).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/divinaluz-*.jar app.jar
# Ajustado para um contêiner pequeno (~512 MB e fração de CPU): heap limitado, GC serial e só o
# compilador C1 (TieredStopAtLevel=1), que aquece bem mais rápido com pouca CPU. Num host folgado
# dá para tirar o TieredStopAtLevel, que troca subida rápida por desempenho em regime.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"
# CDS (Class Data Sharing): uma execução de treino no build grava as classes carregadas em app.jsa,
# e a subida real lê dali. Com 0,1 CPU isso derrubou a subida de ~4 min para ~1 min (medido em
# 2026-09-27). O treino para logo após montar o contexto (spring.context.exit=onRefresh) e não
# precisa de banco: Flyway e o acesso a metadados do Hibernate ficam desligados só nesta execução.
# O -Xmx do treino não é enfeite: o arquivo CDS só vale se o modo dos ponteiros (compressed oops)
# for o mesmo no treino e na subida. Sem ele, o heap sai do MaxRAMPercentage da máquina de BUILD —
# no Render, grande o bastante para passar de 32 GB, o que desliga os compressed oops — e na subida
# (512 MB) eles ficam ligados: a JVM descarta o arquivo ("The saved state of UseCompressedOops ... is
# different from runtime") e sobe sem CDS, de volta à subida lenta. Visto no deploy de 2026-10-03.
RUN java -Djarmode=tools -jar app.jar extract --destination aplicacao && rm app.jar
RUN java -Xmx512m -XX:ArchiveClassesAtExit=aplicacao/app.jsa -Dspring.context.exit=onRefresh \
        -Dspring.main.lazy-initialization=false \
        -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
        -jar aplicacao/app.jar
EXPOSE 8080
# Sem perfil ativo, vale o application.properties: contexto /divinaluz, show-sql ligado e porta por
# PORT (8081 se não vier). Um deploy real passa SPRING_PROFILES_ACTIVE e as variáveis que precisar
# (PORT, DATABASE_URL ou SPRING_DATASOURCE_*) pela plataforma, não por este arquivo.
CMD ["java", "-XX:SharedArchiveFile=aplicacao/app.jsa", "-jar", "aplicacao/app.jar"]
