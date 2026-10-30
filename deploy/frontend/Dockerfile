# LinBlog 前端镜像（文档 §2）：Vite 构建产物直接烘进 Caddy 官方镜像
# 构建上下文必须是仓库根目录（compose 已设置 context: ..）：
#   docker build -f deploy/frontend/Dockerfile .
# 要求 frontend/package.json 声明 "packageManager": "pnpm@<version>"（corepack 据此锁版本），
# 并提交 pnpm-lock.yaml（--frozen-lockfile 依赖它）。

# ── 构建阶段：Node 22 + corepack pnpm ────────────────────────────────────
FROM node:22-alpine AS build
WORKDIR /build

RUN corepack enable

# 先拷清单装依赖，利用层缓存；pnpm-lock.yaml* 允许里程碑早期尚无 lockfile
COPY frontend/package.json frontend/pnpm-lock.yaml* ./
# 有 lockfile 则严格按锁定版本安装；里程碑 0 尚未提交 lockfile 时退回普通安装
RUN if [ -f pnpm-lock.yaml ]; then pnpm install --frozen-lockfile; else pnpm install; fi

COPY frontend/ ./
# Vite 默认输出到 dist/；若前端自定义 outDir 需同步改下一阶段路径
RUN pnpm build

# ── 运行阶段：Caddy 2 ────────────────────────────────────────────────────
FROM caddy:2 AS runtime

# SPA 构建产物就位；Caddyfile 由 compose 挂载（deploy/Caddyfile 或 Caddyfile.local）
COPY --from=build /build/dist /usr/share/caddy
