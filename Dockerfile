FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 appuser && mkdir -p /data && chown appuser:appuser /data
COPY --from=build --chown=appuser:appuser /app/target/*.jar app.jar
USER appuser
ENV APP_DATA_DIR=/data
ENV SESSION_COOKIE_SECURE=true
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
