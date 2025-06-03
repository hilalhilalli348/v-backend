FROM gradle:7.6.3-jdk17 as builder
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle clean build -x test --no-daemon

FROM openjdk:17-slim
RUN apt-get update && apt-get install -y ffmpeg
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"] 