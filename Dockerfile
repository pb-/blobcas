FROM clojure:tools-deps-1.11.1.1165 AS blobcas-builder

RUN mkdir -p /build
WORKDIR /build

# cache deps
COPY deps.edn /build
RUN clojure -P
RUN clojure -P -T:build

COPY build.clj /build
COPY src src/

RUN clojure -T:build uber


# ----------------------------------------------------
FROM gcr.io/distroless/java17-debian11
WORKDIR /
EXPOSE 8080
VOLUME /data
ENV STORAGE_PATH=/data
COPY --from=blobcas-builder /build/target/blobcas.jar /
ENTRYPOINT ["java", "-jar", "blobcas.jar"]
