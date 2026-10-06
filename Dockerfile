# Relay server + web app in one image.
#
# Build the pieces first (needs JDK 17+ and the Android SDK, because the shared modules also target Android):
#   ./gradlew :server:installDist :webApp:wasmJsBrowserDistribution
#   docker build -t streamingservice .
#   docker run -p 8080:8080 streamingservice
#
# Configuration (all optional) is read from environment variables, see README.md.
FROM eclipse-temurin:21-jre

RUN useradd --system --create-home --shell /usr/sbin/nologin app
WORKDIR /app

COPY server/build/install/streamingservice-server/ /app/
COPY webApp/build/dist/wasmJs/productionExecutable/ /app/web/

ENV PORT=8080 \
    STATIC_DIR=/app/web

EXPOSE 8080
USER app
ENTRYPOINT ["/app/bin/streamingservice-server"]
