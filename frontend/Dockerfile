# syntax=docker/dockerfile:1.7
FROM node:24-alpine AS build
WORKDIR /workspace
COPY frontend/package*.json ./
RUN npm ci --no-fund
COPY frontend/ ./
RUN npm run build

FROM nginx:stable-alpine
RUN apk upgrade --no-cache
COPY frontend/nginx.conf /etc/nginx/nginx.conf
COPY frontend/security-headers.conf /etc/nginx/security-headers.conf
COPY --from=build /workspace/dist/notifyhub-web/browser /usr/share/nginx/html
USER 101:101
EXPOSE 8443
ENTRYPOINT ["nginx", "-g", "daemon off;"]
