# stui アプリ（scripts/dist.sh の成果物を詰めるだけ）
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S stui && adduser -S -G stui stui
COPY dist/lib /app/lib
COPY dist/static /app/static
ENV STATIC_DIR=/app/static HTTP_PORT=8080
USER stui
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=70", "--sun-misc-unsafe-memory-access=allow", "-cp", "/app/lib/*", "stui.app.Main"]
