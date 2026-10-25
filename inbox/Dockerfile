# Paigr's inbox server, to run yourself. See README.md.
#   docker build -t paigr-server .
#   docker run -d --name paigr --restart unless-stopped -e TOR=1 -v paigr-data:/data paigr-server
ARG NODE_IMAGE=node:22-alpine
FROM ${NODE_IMAGE}

# tor, for running as an onion service (TOR=1). Nothing else: the server uses only Node's own modules.
RUN apk add --no-cache tor

WORKDIR /app
COPY functions/inbox2.js functions/watch2.js functions/
COPY server/store.js server/paigr-server.js server/
COPY web/ web/

RUN addgroup -S paigr && adduser -S -D -H -G paigr paigr && mkdir -p /data && chown paigr:paigr /data && chmod 700 /data
USER paigr
ENV DATA_DIR=/data PORT=8787 NODE_ENV=production
VOLUME /data
EXPOSE 8787
HEALTHCHECK --interval=60s --timeout=5s --start-period=30s CMD wget -qO /dev/null "http://127.0.0.1:${PORT}/healthz" || exit 1
CMD ["node", "server/paigr-server.js"]
