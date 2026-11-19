FROM eclipse-temurin:25-jdk-noble@sha256:80f67fb3d6f76e2d817d98a4cc2a9cf05f054d4e6adc5e2144bde671d722e63f AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
COPY backend/src/ src/
COPY docs/verification/backend-dependencies/settings.xml ./settings.xml
RUN sh ./mvnw -B -ntp -s settings.xml -gs settings.xml -DskipTests package

FROM eclipse-temurin:25-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c
RUN apt-get update && apt-get install -y --no-install-recommends libxcb1 libxcb-shm0 libasound2t64 libpulse0 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/opennest-backend-0.0.1-SNAPSHOT.jar app.jar
COPY LICENSE docs/THIRD_PARTY_NOTICES.md ./
USER 10001:1000
EXPOSE 10000
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
