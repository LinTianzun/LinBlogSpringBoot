# LinBlog 备份 cron 容器（文档 §7.10）：alpine + busybox crond，每日 03:00 执行一次。
# 依赖 mysql-client（提供 mysqldump，alpine 上为 MariaDB 客户端实现）与 rsync；
# 时区由 compose 注入 TZ，cron 按容器时区触发。

FROM alpine:3.20

RUN apk add --no-cache mysql-client rsync tzdata \
 && mkdir -p /backups /data/storage \
 && printf '0 3 * * * /bin/sh /opt/backup.sh >> /backups/backup.log 2>&1\n' | crontab -

COPY backup.sh /opt/backup.sh
RUN chmod 0755 /opt/backup.sh

# 前台运行 busybox crond，日志走容器 stdout（备份明细落在 /backups/backup.log）
CMD ["crond", "-f", "-l", "8"]
