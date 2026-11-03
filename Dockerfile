FROM maven:3.9-eclipse-temurin-17 AS api-build
WORKDIR /build
COPY backend/api/pom.xml .
RUN mvn -B dependency:go-offline
COPY backend/api/src ./src
RUN mvn -B package -DskipTests

FROM node:22-bookworm-slim AS node-runtime

FROM python:3.11-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node
WORKDIR /app
COPY backend/ai/requirements*.txt /app/backend/ai/
RUN pip install --no-cache-dir -r /app/backend/ai/requirements-gemini.txt
COPY backend/ai/app /app/backend/ai/app
COPY backend/collector/src /app/backend/collector/src
COPY backend/collector/package.json /app/backend/collector/package.json
COPY --from=api-build /build/target/prizm-api-0.1.0.jar /app/api.jar
COPY deploy/start.py /app/deploy/start.py
ENV APP_ENV=production DATA_PROVIDER=reef-danawa AI_PROVIDER=gemini FIREBASE_MODE=cloud \
    SERVER_ADDRESS=0.0.0.0 PORT=10000 AI_URL=http://127.0.0.1:8000 COLLECTOR_URL=http://127.0.0.1:4001 \
    PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50"
RUN groupadd --gid 1000 appgroup && useradd --uid 1000 --gid 1000 --create-home appuser
USER appuser
EXPOSE 10000
CMD ["python", "/app/deploy/start.py"]
