# PushDuel: Next.js + WebSocket в одном процессе.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# copy-mediapipe кладёт WASM и скачивает модель позы в public/mediapipe
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production \
    PORT=3000 \
    DATA_FILE=/data/pushduel.json \
    NEXT_TELEMETRY_DISABLED=1
COPY --from=build /app/package.json /app/next.config.mjs /app/server.mjs ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/lib ./lib
VOLUME /data
EXPOSE 3000
CMD ["node", "server.mjs"]
