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

# Nginx 配置：API 代理到后端
RUN echo 'server { \
    listen 80; \
    server_name localhost; \
    location / { \
        root /usr/share/nginx/html; \
        index index.html; \
        try_files $uri $uri/ /index.html; \
    } \
    location /api/ { \
        proxy_pass http://echomind-backend:8080/api/; \
        proxy_set_header Host $host; \
        proxy_set_header X-Real-IP $remote_addr; \
        proxy_read_timeout 120s; \
    } \
}' > /etc/nginx/conf.d/default.conf

EXPOSE 80
