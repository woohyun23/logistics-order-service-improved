FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

RUN bash gradlew --no-daemon dependencies

COPY . .
RUN bash gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-jar", "app.jar"]
