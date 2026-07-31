FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY target/community-voting-service-*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
