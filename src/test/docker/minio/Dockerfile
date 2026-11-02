# Test-only fixture for RELEASE.2024-09-13T20-26-02Z. The published image and
# archived binary are no longer available; preserve the release's source.
FROM golang:1.22.12-bookworm AS build
RUN CGO_ENABLED=0 GOBIN=/out go install github.com/minio/minio@5bf41aff1702adf2a11fa96a7b68f8651191e24d

FROM alpine:3.23
COPY --from=build /out/minio /usr/local/bin/minio
RUN mkdir /data && chown 65532:65532 /data
USER 65532:65532
EXPOSE 9000
ENTRYPOINT ["minio"]
