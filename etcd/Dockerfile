FROM alpine:3.21
# etcd-ctl даёт etcdctl + нормальный шелл: удобно для ручных правок из Makefile
# (docker compose run --rm etcdctl put ... / del ... / get ...). Стартовые ключи
# кладёт config-provisioner (txn «ключа нет → put»), здесь идемпотентность не нужна.
RUN apk add --no-cache bash etcd-ctl
WORKDIR /seed
ENTRYPOINT ["etcdctl"]
