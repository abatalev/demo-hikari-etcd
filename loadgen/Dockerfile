# Нагрузчик собирается Maven, как и два других образа стенда.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/loadgen.jar /app/loadgen.jar
# sh -c нужен ради $JAVA_OPTS: в exec-форме массива подстановки не будет.
ENV JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=50"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/loadgen.jar"]