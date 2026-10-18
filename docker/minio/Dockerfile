FROM golang:1.23.12-alpine@sha256:383395b794dffa5b53012a212365d40c8e37109a626ca30d6151c8348d380b5f AS build

# Keep the existing storage release. Its former prebuilt image is no longer available.
WORKDIR /src
RUN wget -q -O /tmp/minio.tar.gz https://codeload.github.com/minio/minio/tar.gz/16f8cf1c52f0a77eeb8f7565aaf7f7df12454583 \
    && echo '7cc89d321b06b265012fa4f49a4b00a715bab5eef3a157c1d348a93ead51d1d2  /tmp/minio.tar.gz' | sha256sum -c - \
    && tar -xzf /tmp/minio.tar.gz --strip-components=1 -C /src \
    && rm /tmp/minio.tar.gz
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -p 2 -mod=readonly -trimpath -buildvcs=false \
      -ldflags='-s -w -X github.com/minio/minio/cmd.Version=2024-12-18T13:15:44Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2024-12-18T13-15-44Z -X github.com/minio/minio/cmd.CommitID=16f8cf1c52f0a77eeb8f7565aaf7f7df12454583 -X github.com/minio/minio/cmd.ShortCommitID=16f8cf1c52f0 -X github.com/minio/minio/cmd.CopyrightYear=2024' \
      -o /out/minio .

FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8
RUN apk add --no-cache ca-certificates curl
COPY --from=build /out/minio /usr/bin/minio
COPY --from=build /src/dockerscripts/docker-entrypoint.sh /usr/bin/docker-entrypoint.sh
COPY --from=build /src/LICENSE /licenses/minio/LICENSE
RUN chmod +x /usr/bin/docker-entrypoint.sh
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.revision="16f8cf1c52f0a77eeb8f7565aaf7f7df12454583" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later"
EXPOSE 9000
ENTRYPOINT ["/usr/bin/docker-entrypoint.sh"]
CMD ["minio"]
