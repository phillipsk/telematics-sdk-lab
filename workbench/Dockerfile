FROM node:22-alpine

WORKDIR /app

COPY --chown=node:node server.mjs index.html styles.css ./
COPY --chown=node:node js ./js

USER node

ENV NODE_ENV=production PORT=8080
EXPOSE 8080

CMD ["node", "server.mjs"]
