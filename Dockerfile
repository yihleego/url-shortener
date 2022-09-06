FROM openjdk

COPY *.jar /app.jar

EXPOSE 18080

ENTRYPOINT ["java", "-jar", "/app.jar"]