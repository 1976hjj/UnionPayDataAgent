# syntax=docker/dockerfile:1

FROM node:22-alpine AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
COPY --from=frontend-build /workspace/frontend/dist ./src/main/resources/static
RUN mvn -B -DskipTests -Dskip.frontend=true clean package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home appuser
COPY --from=backend-build /workspace/target/payment-analysis.jar /app/app.jar
USER appuser
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
