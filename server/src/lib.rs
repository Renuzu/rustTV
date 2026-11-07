//! rustTV server: serves the web app and proxies Xtream Codes API calls,
//! HLS playlists, video streams and images, so that browsers can play
//! streams that would otherwise be blocked (mixed content / CORS).

use axum::{
    body::Body,
    extract::{Query, Request, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::TryStreamExt;
use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC};
use rust_embed::RustEmbed;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{collections::HashMap, sync::Arc, time::Duration};
use tokio::net::TcpListener;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(RustEmbed)]
#[folder = "../web/dist"]
struct Assets;

/// Server settings.
#[derive(Clone, Debug, Default)]
pub struct Config {
    /// Optional password protecting the whole instance.
    pub password: Option<String>,
    /// User-Agent sent to the IPTV provider.
    pub user_agent: String,
    /// "server" or "desktop" – reported to the web app.
    pub mode: String,
}

impl Config {
    pub fn from_env() -> Self {
        Self {
            password: std::env::var("RUSTV_PASSWORD")
                .ok()
                .filter(|p| !p.is_empty()),
            user_agent: std::env::var("RUSTV_USER_AGENT")
                .unwrap_or_else(|_| format!("rustTV/{VERSION}")),
            mode: "server".into(),
        }
    }
}

struct AppState {
    cfg: Config,
    http: reqwest::Client,
    token: Option<String>,
}

type Shared = Arc<AppState>;

fn token_for(pw: &str) -> String {
    let mut h = Sha256::new();
    h.update(b"rustv-session:");
    h.update(pw.as_bytes());
    hex::encode(h.finalize())
}

/// Build the router (used by the standalone server and the desktop app).
pub fn router(cfg: Config) -> Router {
    let http = reqwest::Client::builder()
        .user_agent(cfg.user_agent.clone())
        .connect_timeout(Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::limited(10))
        .build()
        .expect("http client");
    let token = cfg.password.as_deref().map(token_for);
    let state = Arc::new(AppState { cfg, http, token });

    let api = Router::new()
        .route("/api/xtream", get(xtream))
        .route("/api/p", get(proxy))
        .layer(middleware::from_fn_with_state(state.clone(), require_auth));

    Router::new()
        .route("/api/info", get(info))
        .route("/api/login", post(login))
        .merge(api)
        .fallback(static_file)
        .with_state(state)
}

/// Run the server on an existing listener until shutdown.
pub async fn serve(listener: TcpListener, cfg: Config) -> std::io::Result<()> {
    axum::serve(listener, router(cfg)).await
}

// ---------------------------------------------------------------- auth

fn cookie_token(headers: &HeaderMap) -> Option<String> {
    let raw = headers.get(header::COOKIE)?.to_str().ok()?;
    raw.split(';')
        .filter_map(|c| c.trim().split_once('='))
        .find(|(k, _)| *k == "rustv_auth")
        .map(|(_, v)| v.to_string())
}

fn is_authed(st: &AppState, headers: &HeaderMap) -> bool {
    match &st.token {
        None => true,
        Some(t) => cookie_token(headers).as_deref() == Some(t.as_str()),
    }
}

async fn require_auth(State(st): State<Shared>, req: Request, next: Next) -> Response {
    if is_authed(&st, req.headers()) {
        next.run(req).await
    } else {
        (StatusCode::UNAUTHORIZED, "login required").into_response()
    }
}

async fn info(State(st): State<Shared>, headers: HeaderMap) -> impl IntoResponse {
    Json(serde_json::json!({
        "name": "rustTV",
        "version": VERSION,
        "mode": st.cfg.mode,
        "auth_required": st.token.is_some(),
        "authed": is_authed(&st, &headers),
    }))
}

#[derive(Deserialize)]
struct LoginBody {
    password: String,
}

async fn login(State(st): State<Shared>, Json(body): Json<LoginBody>) -> Response {
    match &st.cfg.password {
        Some(pw) if *pw == body.password => {
            let cookie = format!(
                "rustv_auth={}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000",
                token_for(pw)
            );
            let mut r = StatusCode::NO_CONTENT.into_response();
            r.headers_mut()
                .insert(header::SET_COOKIE, HeaderValue::from_str(&cookie).unwrap());
            r
        }
        Some(_) => (StatusCode::UNAUTHORIZED, "wrong password").into_response(),
        None => StatusCode::NO_CONTENT.into_response(),
    }
}

// ---------------------------------------------------------------- xtream api

/// `/api/xtream?server=..&username=..&password=..&action=..` → `{server}/player_api.php?...`
async fn xtream(State(st): State<Shared>, Query(q): Query<HashMap<String, String>>) -> Response {
    let Some(server) = q.get("server") else {
        return (StatusCode::BAD_REQUEST, "missing server").into_response();
    };
    let base = normalize_server(server);
    let mut url = match url::Url::parse(&format!("{base}/player_api.php")) {
        Ok(u) if u.scheme() == "http" || u.scheme() == "https" => u,
        _ => return (StatusCode::BAD_REQUEST, "invalid server").into_response(),
    };
    {
        let mut qp = url.query_pairs_mut();
        for (k, v) in &q {
            if k != "server" {
                qp.append_pair(k, v);
            }
        }
    }
    match st
        .http
        .get(url)
        .timeout(Duration::from_secs(90))
        .send()
        .await
    {
        Ok(resp) => {
            let status = resp.status();
            match resp.bytes().await {
                Ok(b) => Response::builder()
                    .status(status.as_u16())
                    .header(header::CONTENT_TYPE, "application/json; charset=utf-8")
                    .header(header::CACHE_CONTROL, "no-store")
                    .body(Body::from(b))
                    .unwrap(),
                Err(e) => (StatusCode::BAD_GATEWAY, e.to_string()).into_response(),
            }
        }
        Err(e) => (
            StatusCode::BAD_GATEWAY,
            format!("provider not reachable: {e}"),
        )
            .into_response(),
    }
}

pub fn normalize_server(s: &str) -> String {
    let mut u = s.trim().to_string();
    if !u.starts_with("http://") && !u.starts_with("https://") {
        u = format!("http://{u}");
    }
    for suffix in ["/player_api.php", "/get.php"] {
        if let Some(x) = u.strip_suffix(suffix) {
            u = x.to_string();
        }
    }
    u.trim_end_matches('/').to_string()
}

// ---------------------------------------------------------------- stream / image proxy

#[derive(Deserialize)]
struct ProxyQuery {
    u: String,
}

fn proxied(abs: &str) -> String {
    format!("/api/p?u={}", utf8_percent_encode(abs, NON_ALPHANUMERIC))
}

/// Rewrite every URL inside an HLS playlist so it goes through this proxy.
pub fn rewrite_m3u8(text: &str, base: &url::Url) -> String {
    let mut out = String::with_capacity(text.len() + 512);
    for line in text.lines() {
        let t = line.trim();
        if t.is_empty() {
            out.push('\n');
            continue;
        }
        if t.starts_with('#') {
            // URI="..." attributes (keys, media playlists, maps)
            if let Some(start) = t.find("URI=\"") {
                let rest = &t[start + 5..];
                if let Some(end) = rest.find('"') {
                    let uri = &rest[..end];
                    if let Ok(abs) = base.join(uri) {
                        out.push_str(&t[..start + 5]);
                        out.push_str(&proxied(abs.as_str()));
                        out.push_str(&rest[end..]);
                        out.push('\n');
                        continue;
                    }
                }
            }
            out.push_str(t);
        } else if let Ok(abs) = base.join(t) {
            out.push_str(&proxied(abs.as_str()));
        } else {
            out.push_str(t);
        }
        out.push('\n');
    }
    out
}

async fn proxy(
    State(st): State<Shared>,
    headers: HeaderMap,
    Query(q): Query<ProxyQuery>,
) -> Response {
    let target = match url::Url::parse(&q.u) {
        Ok(u) if u.scheme() == "http" || u.scheme() == "https" => u,
        _ => return (StatusCode::BAD_REQUEST, "invalid url").into_response(),
    };
    let mut req = st.http.get(target.clone());
    if let Some(r) = headers.get(header::RANGE) {
        req = req.header(header::RANGE, r.clone());
    }
    let resp = match req.send().await {
        Ok(r) => r,
        Err(e) => return (StatusCode::BAD_GATEWAY, format!("upstream error: {e}")).into_response(),
    };
    let status = resp.status();
    let final_url = resp.url().clone();
    let ctype = resp
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();
    let is_playlist = ctype.contains("mpegurl")
        || final_url.path().ends_with(".m3u8")
        || target.path().ends_with(".m3u8");

    if is_playlist {
        let text = match resp.text().await {
            Ok(t) => t,
            Err(e) => return (StatusCode::BAD_GATEWAY, e.to_string()).into_response(),
        };
        if text.trim_start().starts_with("#EXTM3U") {
            return Response::builder()
                .status(status.as_u16())
                .header(header::CONTENT_TYPE, "application/vnd.apple.mpegurl")
                .header(header::CACHE_CONTROL, "no-store")
                .body(Body::from(rewrite_m3u8(&text, &final_url)))
                .unwrap();
        }
        return Response::builder()
            .status(status.as_u16())
            .header(header::CONTENT_TYPE, ctype)
            .body(Body::from(text))
            .unwrap();
    }

    let mut b = Response::builder().status(status.as_u16());
    for h in [
        header::CONTENT_TYPE,
        header::CONTENT_LENGTH,
        header::CONTENT_RANGE,
        header::ACCEPT_RANGES,
        header::CACHE_CONTROL,
        header::LAST_MODIFIED,
        header::ETAG,
    ] {
        if let Some(v) = resp.headers().get(&h) {
            b = b.header(h, v.clone());
        }
    }
    let stream = resp.bytes_stream().map_err(std::io::Error::other);
    b.body(Body::from_stream(stream)).unwrap()
}

// ---------------------------------------------------------------- static web app

async fn static_file(req: Request) -> Response {
    let path = req.uri().path().trim_start_matches('/');
    let path = if path.is_empty() { "index.html" } else { path };
    if let Some(f) = Assets::get(path) {
        return file_response(path, f.data.into_owned());
    }
    if path.starts_with("api/") {
        return StatusCode::NOT_FOUND.into_response();
    }
    // single-page app fallback
    match Assets::get("index.html") {
        Some(f) => file_response("index.html", f.data.into_owned()),
        None => (StatusCode::NOT_FOUND, "web app not built").into_response(),
    }
}

fn file_response(path: &str, data: Vec<u8>) -> Response {
    let mime = mime_guess::from_path(path).first_or_octet_stream();
    let cache = if path == "index.html" || path == "sw.js" || path.ends_with(".webmanifest") {
        "no-cache"
    } else {
        "public, max-age=31536000, immutable"
    };
    let mut r = Response::new(Body::from(data));
    r.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(mime.as_ref()).unwrap(),
    );
    r.headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static(cache));
    r
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_server() {
        assert_eq!(
            normalize_server("example.com:8080/"),
            "http://example.com:8080"
        );
        assert_eq!(
            normalize_server("https://x.tv/player_api.php"),
            "https://x.tv"
        );
    }

    #[test]
    fn rewrites_playlist() {
        let base = url::Url::parse("http://cdn.example/live/a/b/1.m3u8").unwrap();
        let out = rewrite_m3u8(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\nseg1.ts\nhttp://other/seg2.ts\n",
            &base,
        );
        assert!(out.contains("/api/p?u=http%3A%2F%2Fcdn%2Eexample%2Flive%2Fa%2Fb%2Fseg1%2Ets"));
        assert!(
            out.contains("URI=\"/api/p?u=http%3A%2F%2Fcdn%2Eexample%2Flive%2Fa%2Fb%2Fkey%2Ebin\"")
        );
        assert!(out.contains("/api/p?u=http%3A%2F%2Fother%2Fseg2%2Ets"));
    }
}
