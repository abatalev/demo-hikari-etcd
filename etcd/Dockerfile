FROM alpine:3.21
# etcd-ctl даёт etcdctl + нормальный шелл: один образ и для сидинга конфига,
# и для ручных изменений из Makefile (docker compose run --rm etcdctl put ...)
RUN apk add --no-cache bash etcd-ctl
WORKDIR /seed
ENTRYPOINT ["etcdctl"]
