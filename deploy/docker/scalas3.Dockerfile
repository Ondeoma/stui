# 開発・試験用の S3 互換ストレージ（ScalaS3、https://github.com/Ondeoma/ScalaS3）。
# 公開イメージがないため、決まったコミットのソースからビルドする（初回は数分かかる）。
# 版を上げるときは SCALAS3_REF を変える。
FROM eclipse-temurin:25-jdk AS build
ARG SBT_VERSION=1.12.6
ARG SCALAS3_REF=913fea8629fde58f987efda25b177b28234f7aed
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz | tar -xz -C /opt
RUN git clone https://github.com/Ondeoma/ScalaS3.git /src && git -C /src checkout --detach ${SCALAS3_REF}
WORKDIR /src
RUN /opt/sbt/bin/sbt -batch server/assembly

FROM eclipse-temurin:25-jre
# ボリュームの所有者を scalas3 にするため、データのディレクトリを先に作っておく
RUN useradd --system --home /var/lib/scalas3 --create-home scalas3 \
 && mkdir -p /var/lib/scalas3/data && chown scalas3:scalas3 /var/lib/scalas3/data
COPY --from=build /src/server/target/scala-3.9.0/scalas3-server.jar /opt/scalas3/scalas3-server.jar
USER scalas3
WORKDIR /var/lib/scalas3
ENV SCALAS3_DATA=/var/lib/scalas3/data \
    SCALAS3_S3_LISTEN=0.0.0.0:3900 \
    SCALAS3_RPC_LISTEN=127.0.0.1:3901 \
    JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=50"
VOLUME ["/var/lib/scalas3/data"]
EXPOSE 3900
# S3 API の待ち受けが開いたら準備完了
HEALTHCHECK --interval=2s --retries=60 CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/3900'
ENTRYPOINT ["java", "-jar", "/opt/scalas3/scalas3-server.jar"]
