# =============================================================================
# online-chat frontend Dockerfile
# 多阶段构建: node:18-alpine 编译 → nginx:alpine 静态服务
# 输出 SPA 静态文件, 由 nginx 托管, /api/ /ws/ 反代到后端 Gateway
# =============================================================================

# ---------- Stage 1: 构建 ----------
FROM node:18-alpine AS build
WORKDIR /build

# 优先复制 package 文件, 利于缓存依赖
COPY package.json package-lock.json* ./

# 加速: 使用国内淘宝镜像 (国内构建时)
RUN npm config set registry https://registry.npmmirror.com \
 && npm install --no-audit --no-fund --loglevel=error

# 复制源码 + 构建
COPY . .
RUN npm run build

# ---------- Stage 2: 运行时 ----------
FROM nginx:1.27-alpine AS runtime
LABEL service="chat-frontend" \
      port="80"

# 时区 (alpine)
RUN apk add --no-cache tzdata curl \
 && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
 && echo "Asia/Shanghai" > /etc/timezone \
 && apk del tzdata

# 自定义 nginx 配置
COPY nginx.conf /etc/nginx/templates/default.conf.template
# 启用 envsubst 模板渲染 (default.conf 来自 templates/*.template)
# 官方 nginx 镜像在 entrypoint.d 自动跑 envsubst

# SPA 静态产物
COPY --from=build /build/dist /usr/share/nginx/html

# 健康检查
HEALTHCHECK --interval=15s --timeout=3s --retries=5 --start-period=10s \
  CMD curl -fsS http://127.0.0.1/healthz || exit 1

EXPOSE 80
