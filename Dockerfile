# Build ruSTv from source (used by `docker compose build` and for local builds).
# The published image (ghcr.io/renuzu/rustv) is built in CI from release binaries.
FROM rust:1-bookworm AS build
RUN rustup target add wasm32-unknown-unknown \
 && cargo install trunk --locked
WORKDIR /src
COPY . .
RUN cd web && trunk build --release \
 && cd .. && cargo build --release -p rustv-server

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 rustv
COPY --from=build /src/target/release/rustv /usr/local/bin/rustv
USER rustv
ENV RUSTV_BIND=0.0.0.0:8080
EXPOSE 8080
CMD ["rustv"]
