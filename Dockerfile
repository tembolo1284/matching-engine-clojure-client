FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /app

COPY deps.edn ./
RUN clojure -P

COPY package.json shadow-cljs.edn ./
RUN apk add --no-cache nodejs npm && npm install

COPY src/ src/
COPY resources/ resources/

RUN clojure -X:build-relay
RUN npm run release

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/target/me-relay.jar ./relay.jar
COPY --from=builder /app/resources/public/ ./public/

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "relay.jar"]
CMD ["--engine", "host.docker.internal:1234", "--ws-port", "8080"]
