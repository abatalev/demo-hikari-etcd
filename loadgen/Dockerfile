FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY LoadGen.java .
RUN javac -d out LoadGen.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/out/ ./
ENV JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=50"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp /app LoadGen"]
