#!/bin/sh
# LinBlog 每日备份脚本（文档 §7.10；alpine busybox sh）
# 职责：
#   1. mysqldump 全量导出应用库 -> gzip 落到 $BACKUP_DIR，滚动保留 $BACKUP_KEEP_DAYS 天
#   2. 备份区 2GiB 上限检查 + 80% 告警（写日志，沿用原项目 files 配额口径 §3.5）
#   3. 可选：rsync storage 目录 -> $OFFSITE_BACKUP_DIR 异地副本（留空则跳过）
set -eu

: "${BACKUP_DIR:=/backups}"
: "${BACKUP_KEEP_DAYS:=14}"
: "${BACKUP_QUOTA_BYTES:=2147483648}"      # 2 GiB
: "${DB_HOST:=mysql}"
: "${DB_PORT:=3306}"
: "${DB_USERNAME:?DB_USERNAME 未设置}"
: "${DB_PASSWORD:?DB_PASSWORD 未设置}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE 未设置}"
: "${STORAGE_LOCAL_DIR:=/data/storage}"    # storage 卷在 backup 容器内的挂载点（只读）
: "${OFFSITE_BACKUP_DIR:=}"                # 留空 = 不做异地副本

log() { printf '[backup] %s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

mkdir -p "$BACKUP_DIR"
TS="$(date +%Y%m%d_%H%M%S)"
DUMP="$BACKUP_DIR/linblog_${TS}.sql.gz"

log "开始 mysqldump（库：${MYSQL_DATABASE}）-> $DUMP"
# --single-transaction：InnoDB 一致性快照不锁表；--hex-blob：二进制安全；
# --databases：带上 CREATE DATABASE/USE 语句，空实例可直接恢复。
# 如需连 mysql/sys 系统库一起导，可改为 --all-databases（恢复时须版本匹配）。
MYSQL_PWD="$DB_PASSWORD" mysqldump \
  -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" \
  --databases "$MYSQL_DATABASE" \
  --single-transaction --routines --events --triggers --hex-blob \
  | gzip -c > "$DUMP"

if [ ! -s "$DUMP" ]; then
  log "ERROR: 备份文件为空，导出可能失败，已删除 $DUMP"
  rm -f "$DUMP"
  exit 1
fi
log "mysqldump 完成，大小 $(du -h "$DUMP" | cut -f1)"

# 滚动删除：只保留最近 BACKUP_KEEP_DAYS 天
find "$BACKUP_DIR" -name 'linblog_*.sql.gz' -type f -mtime +"$BACKUP_KEEP_DAYS" -delete
log "已清理 ${BACKUP_KEEP_DAYS} 天前的旧备份"

# ── 备份区配额检查：上限 BACKUP_QUOTA_BYTES（默认 2GiB），80% 告警 ────────
USED_KB="$(du -sk "$BACKUP_DIR" | cut -f1)"
USED="$(( USED_KB * 1024 ))"
PCT="$(( USED * 100 / BACKUP_QUOTA_BYTES ))"
log "备份区用量 ${USED} / ${BACKUP_QUOTA_BYTES} 字节（${PCT}%）"
if [ "$PCT" -ge 100 ]; then
  log "ERROR: 备份区已超过 ${BACKUP_QUOTA_BYTES} 字节上限（${PCT}%），请立即清理或扩容"
  exit 2
elif [ "$PCT" -ge 80 ]; then
  log "WARN: 备份区用量已达 ${PCT}%（≥80% 告警阈值），请关注清理"
fi

# ── 可选：storage 目录异地副本（沿用原项目「跨盘镜像」思路）──────────────
if [ -n "$OFFSITE_BACKUP_DIR" ]; then
  log "rsync storage -> ${OFFSITE_BACKUP_DIR}/storage"
  mkdir -p "${OFFSITE_BACKUP_DIR}/storage"
  rsync -a --delete "${STORAGE_LOCAL_DIR}/" "${OFFSITE_BACKUP_DIR}/storage/"
  log "rsync dumps -> ${OFFSITE_BACKUP_DIR}/dumps"
  mkdir -p "${OFFSITE_BACKUP_DIR}/dumps"
  rsync -a "${BACKUP_DIR}/" "${OFFSITE_BACKUP_DIR}/dumps/"
  log "异地副本完成"
else
  log "OFFSITE_BACKUP_DIR 未设置，跳过异地副本"
fi

log "备份流程结束"
