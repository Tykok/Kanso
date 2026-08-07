# ---- deps ----
FROM node:20-alpine AS deps
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

# ---- build ----
FROM node:20-alpine AS build
WORKDIR /app
RUN corepack enable
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# NEXT_PUBLIC_* is inlined at build time, so the API origin has to be known here.
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
RUN pnpm build

# ---- run ----
FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
RUN addgroup -g 10001 kanso && adduser -S -u 10001 -G kanso kanso
COPY --from=build --chown=kanso:kanso /app/.next/standalone ./
COPY --from=build --chown=kanso:kanso /app/.next/static ./.next/static
COPY --from=build --chown=kanso:kanso /app/public ./public
USER kanso
EXPOSE 3000
CMD ["node", "server.js"]
