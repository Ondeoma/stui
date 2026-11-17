# Patroni + PostgreSQL 17 + PGroonga（試験用。本番VMでは OS パッケージで同等の構成にする）
FROM groonga/pgroonga:4.1.0-alpine-17-slim
RUN apk add --no-cache python3 py3-pip py3-psycopg2 py3-yaml curl \
 && pip install --no-cache-dir --break-system-packages "patroni[etcd3]==4.0.4" \
 && mkdir -p /var/lib/postgresql/patroni /etc/patroni \
 && chown -R postgres:postgres /var/lib/postgresql /etc/patroni
COPY deploy/docker/patroni.yml /etc/patroni/patroni.yml
COPY deploy/docker/post_bootstrap.sh /etc/patroni/post_bootstrap.sh
RUN chmod +x /etc/patroni/post_bootstrap.sh
USER postgres
EXPOSE 5432 8008
ENTRYPOINT []
CMD ["patroni", "/etc/patroni/patroni.yml"]
