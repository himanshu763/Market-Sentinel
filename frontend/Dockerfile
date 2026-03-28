# ─────────────────────────────────────────────
#  Stage 1 — Build
#  Node 20 builds the Vite/React app into
#  static files in /app/dist
# ─────────────────────────────────────────────
FROM node:20-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build


# ─────────────────────────────────────────────
#  Stage 2 — Serve
#  Nginx serves the static dist/ folder and
#  proxies /api/* calls to the Spring Boot app.
#  Final image is ~25 MB.
# ─────────────────────────────────────────────
FROM nginx:alpine

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
