# ============================
# EchoMind 前端 Dockerfile
# Vue 3 + Vite 构建 → Nginx 部署
# ============================

# ---- 阶段1: 编译 ----
FROM node:20-alpine AS build
WORKDIR /app

# 复制 package.json 和 lock
COPY package.json package-lock.json ./

# 安装依赖
RUN npm install --registry=https://registry.npmmirror.com

# 复制源码并构建
COPY . .
RUN npm run build

# ---- 阶段2: Nginx 部署 ----
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html/

# Nginx 配置：API 代理到后端 + SSE 支持
RUN echo 'server { \
    listen 80; \
    server_name localhost; \
    resolver 127.0.0.11 valid=10s ipv6=off; \
    gzip on; \
    gzip_comp_level 6; \
    gzip_min_length 1024; \
    gzip_types text/plain text/css application/javascript application/json application/xml image/svg+xml; \
    client_max_body_size 55m; \
    set $backend_upstream echomind-backend:8080; \
    location / { \
        root /usr/share/nginx/html; \
        index index.html; \
        try_files $uri $uri/ /index.html; \
    } \
    location /api/ { \
        proxy_pass http://$backend_upstream$request_uri; \
        proxy_set_header Host $host; \
        proxy_set_header X-Real-IP $remote_addr; \
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; \
        proxy_set_header Cache-Control no-cache; \
        proxy_read_timeout 600s; \
        proxy_http_version 1.1; \
        proxy_set_header Connection ""; \
        proxy_buffering off; \
        chunked_transfer_encoding on; \
    } \
}' > /etc/nginx/conf.d/default.conf

EXPOSE 80
