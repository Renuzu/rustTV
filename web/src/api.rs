//! Talks to the Xtream Codes API through the rustTV server (`/api/xtream`).

use crate::store::{t, Account};
use base64::Engine;
use gloo_net::http::Request;
use serde_json::Value;

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Kind {
    Live,
    Movie,
    Series,
}

impl Kind {
    pub const ALL: [Kind; 3] = [Kind::Live, Kind::Movie, Kind::Series];
    pub fn key(self) -> &'static str {
        match self {
            Kind::Live => "live",
            Kind::Movie => "movie",
            Kind::Series => "series",
        }
    }
    pub fn from_key(s: &str) -> Kind {
        match s {
            "movie" => Kind::Movie,
            "series" => Kind::Series,
            _ => Kind::Live,
        }
    }
    pub fn title(self) -> &'static str {
        match self {
            Kind::Live => t("Live-TV", "Live TV"),
            Kind::Movie => t("Filme", "Movies"),
            Kind::Series => t("Serien", "Series"),
        }
    }
    pub fn icon(self) -> &'static str {
        match self {
            Kind::Live => "📺",
            Kind::Movie => "🎬",
            Kind::Series => "🎞️",
        }
    }
    fn cat_action(self) -> &'static str {
        match self {
            Kind::Live => "get_live_categories",
            Kind::Movie => "get_vod_categories",
            Kind::Series => "get_series_categories",
        }
    }
    fn list_action(self) -> &'static str {
        match self {
            Kind::Live => "get_live_streams",
            Kind::Movie => "get_vod_streams",
            Kind::Series => "get_series",
        }
    }
}

#[derive(Clone, PartialEq, Debug)]
pub struct Category {
    pub id: String,
    pub name: String,
}

#[derive(Clone, PartialEq, Debug)]
pub struct Entry {
    pub id: String,
    pub name: String,
    pub cat: String,
    pub icon: Option<String>,
    pub ext: Option<String>,
    pub sub: Option<String>,
    pub archive: bool,
}

#[derive(Clone, PartialEq, Debug)]
pub struct Programme {
    pub title: String,
    pub desc: String,
    pub start_text: String,
    pub start_ts: i64,
    pub end_ts: i64,
    pub has_archive: bool,
}

pub fn enc(s: &str) -> String {
    String::from(js_sys::encode_uri_component(s))
}

/// Reads a field as string – providers mix numbers, strings and null.
fn s(v: &Value, key: &str) -> Option<String> {
    match v.get(key)? {
        Value::Null => None,
        Value::String(x) => {
            let x = x.trim();
            if x.is_empty() || x == "null" {
                None
            } else {
                Some(x.to_string())
            }
        }
        other => Some(other.to_string()),
    }
}

/// True in the stand-alone TV app (Samsung): no rustTV server, the app talks
/// to the provider directly. Set by `direct.js` (`window.rustvDirect = true`).
pub fn direct() -> bool {
    web_sys::window()
        .and_then(|w| js_sys::Reflect::get(&w, &"rustvDirect".into()).ok())
        .map(|v| v.is_truthy())
        .unwrap_or(false)
}

async fn call(acc: &Account, action: Option<&str>, extra: &str) -> Result<Value, String> {
    let mut url = if direct() {
        format!(
            "{}/player_api.php?username={}&password={}",
            base(acc),
            enc(&acc.user),
            enc(&acc.pass)
        )
    } else {
        format!(
            "/api/xtream?server={}&username={}&password={}",
            enc(&acc.server),
            enc(&acc.user),
            enc(&acc.pass)
        )
    };
    if let Some(a) = action {
        url.push_str("&action=");
        url.push_str(a);
    }
    url.push_str(extra);
    let resp = Request::get(&url).send().await.map_err(|e| e.to_string())?;
    if resp.status() == 401 {
        return Err("auth".into());
    }
    let text = resp.text().await.map_err(|e| e.to_string())?;
    if !(200..300).contains(&resp.status()) {
        return Err(format!(
            "{} {}",
            resp.status(),
            text.chars().take(200).collect::<String>()
        ));
    }
    serde_json::from_str(&text).map_err(|_| {
        t(
            "Unerwartete Antwort vom Anbieter",
            "Unexpected answer from provider",
        )
        .to_string()
    })
}

/// Checks the login. `Ok(())` on success.
pub async fn login(acc: &Account) -> Result<(), String> {
    let v = call(acc, None, "").await?;
    let info = v
        .get("user_info")
        .ok_or(t("Anmeldung fehlgeschlagen", "Login failed"))?;
    if s(info, "auth").as_deref() != Some("1") {
        return Err(t(
            "Benutzername oder Passwort falsch",
            "Wrong username or password",
        )
        .into());
    }
    if let Some(st) = s(info, "status") {
        if !st.eq_ignore_ascii_case("active") {
            return Err(format!("Status: {st}"));
        }
    }
    Ok(())
}

pub async fn account_info(acc: &Account) -> Result<Vec<(String, String)>, String> {
    let v = call(acc, None, "").await?;
    let u = v.get("user_info").cloned().unwrap_or(Value::Null);
    let exp = s(&u, "exp_date")
        .and_then(|x| x.parse::<f64>().ok())
        .map(|x| {
            let d = js_sys::Date::new(&(x * 1000.0).into());
            String::from(d.to_locale_date_string(
                if crate::store::is_de() {
                    "de-DE"
                } else {
                    "en-GB"
                },
                &Default::default(),
            ))
        })
        .unwrap_or_else(|| t("unbegrenzt / unbekannt", "unlimited / unknown").into());
    Ok(vec![
        ("Status".into(), s(&u, "status").unwrap_or("?".into())),
        (t("Gültig bis", "Expires").into(), exp),
        (
            t("Verbindungen", "Connections").into(),
            format!(
                "{} / {}",
                s(&u, "active_cons").unwrap_or("?".into()),
                s(&u, "max_connections").unwrap_or("?".into())
            ),
        ),
    ])
}

pub async fn categories(acc: &Account, kind: Kind) -> Result<Vec<Category>, String> {
    let v = call(acc, Some(kind.cat_action()), "").await?;
    Ok(v.as_array()
        .map(|a| {
            a.iter()
                .map(|o| Category {
                    id: s(o, "category_id").unwrap_or_default(),
                    name: s(o, "category_name").unwrap_or("?".into()),
                })
                .collect()
        })
        .unwrap_or_default())
}

pub async fn entries(acc: &Account, kind: Kind) -> Result<Vec<Entry>, String> {
    let v = call(acc, Some(kind.list_action()), "").await?;
    let arr = v.as_array().cloned().unwrap_or_default();
    Ok(arr
        .iter()
        .filter_map(|o| {
            let (id, icon) = match kind {
                Kind::Series => (s(o, "series_id")?, s(o, "cover")),
                _ => (s(o, "stream_id")?, s(o, "stream_icon")),
            };
            Some(Entry {
                id,
                name: s(o, "name").unwrap_or("?".into()),
                cat: s(o, "category_id").unwrap_or_default(),
                icon,
                ext: if kind == Kind::Movie {
                    Some(s(o, "container_extension").unwrap_or("mp4".into()))
                } else {
                    None
                },
                sub: None,
                archive: kind == Kind::Live && s(o, "tv_archive").as_deref() == Some("1"),
            })
        })
        .collect())
}

/// Episodes sorted by season and number. `cat` holds the season number.
pub async fn episodes(acc: &Account, series_id: &str) -> Result<Vec<Entry>, String> {
    let v = call(
        acc,
        Some("get_series_info"),
        &format!("&series_id={}", enc(series_id)),
    )
    .await?;
    let mut out: Vec<(i64, i64, Entry)> = Vec::new();
    let mut add = |season: i64, o: &Value| {
        let Some(id) = s(o, "id") else { return };
        let num = s(o, "episode_num")
            .and_then(|x| x.parse().ok())
            .unwrap_or(0);
        let title = s(o, "title").unwrap_or_else(|| format!("{} {num}", t("Folge", "Episode")));
        let icon = o.get("info").and_then(|i| s(i, "movie_image"));
        out.push((
            season,
            num,
            Entry {
                id,
                name: title,
                cat: season.to_string(),
                icon,
                ext: Some(s(o, "container_extension").unwrap_or("mp4".into())),
                sub: Some(format!(
                    "{} {season} · {} {num}",
                    t("Staffel", "Season"),
                    t("Folge", "Episode")
                )),
                archive: false,
            },
        ));
    };
    match v.get("episodes") {
        Some(Value::Object(m)) => {
            for (k, list) in m {
                let season = k.parse().unwrap_or(0);
                for o in list.as_array().into_iter().flatten() {
                    add(season, o);
                }
            }
        }
        Some(Value::Array(seasons)) => {
            for (i, list) in seasons.iter().enumerate() {
                for o in list.as_array().into_iter().flatten() {
                    let season = s(o, "season")
                        .and_then(|x| x.parse().ok())
                        .unwrap_or(i as i64 + 1);
                    add(season, o);
                }
            }
        }
        _ => {}
    }
    out.sort_by_key(|(s, n, _)| (*s, *n));
    Ok(out.into_iter().map(|(_, _, e)| e).collect())
}

fn b64(x: Option<String>) -> String {
    let x = x.unwrap_or_default();
    base64::engine::general_purpose::STANDARD
        .decode(x.as_bytes())
        .ok()
        .and_then(|b| String::from_utf8(b).ok())
        .unwrap_or(x)
}

fn parse_epg(v: &Value) -> Vec<Programme> {
    let mut list: Vec<Programme> = v
        .get("epg_listings")
        .and_then(|a| a.as_array())
        .into_iter()
        .flatten()
        .map(|o| Programme {
            title: b64(s(o, "title")),
            desc: b64(s(o, "description")),
            start_text: s(o, "start").unwrap_or_default(),
            start_ts: s(o, "start_timestamp")
                .and_then(|x| x.parse().ok())
                .unwrap_or(0),
            end_ts: s(o, "stop_timestamp")
                .or_else(|| s(o, "end_timestamp"))
                .and_then(|x| x.parse().ok())
                .unwrap_or(0),
            has_archive: s(o, "has_archive").as_deref() == Some("1"),
        })
        .collect();
    list.sort_by_key(|p| p.start_ts);
    list
}

pub async fn short_epg(acc: &Account, stream_id: &str, limit: u32) -> Vec<Programme> {
    call(
        acc,
        Some("get_short_epg"),
        &format!("&stream_id={}&limit={limit}", enc(stream_id)),
    )
    .await
    .map(|v| parse_epg(&v))
    .unwrap_or_default()
}

pub async fn full_epg(acc: &Account, stream_id: &str) -> Vec<Programme> {
    let l = call(
        acc,
        Some("get_simple_data_table"),
        &format!("&stream_id={}", enc(stream_id)),
    )
    .await
    .map(|v| parse_epg(&v))
    .unwrap_or_default();
    if l.is_empty() {
        short_epg(acc, stream_id, 30).await
    } else {
        l
    }
}

// ------------------------------------------------------------ URLs

fn base(acc: &Account) -> String {
    let mut u = acc.server.trim().to_string();
    if !u.starts_with("http://") && !u.starts_with("https://") {
        u = format!("http://{u}");
    }
    for suf in ["/player_api.php", "/get.php"] {
        if let Some(x) = u.strip_suffix(suf) {
            u = x.to_string();
        }
    }
    u.trim_end_matches('/').to_string()
}

/// Direct provider URL (contains the login – never shown in the UI).
pub fn stream_url(acc: &Account, kind: Kind, e: &Entry, ext: &str) -> String {
    let (u, p) = (enc(&acc.user), enc(&acc.pass));
    match kind {
        Kind::Live => format!("{}/live/{u}/{p}/{}.{ext}", base(acc), e.id),
        Kind::Movie => format!(
            "{}/movie/{u}/{p}/{}.{}",
            base(acc),
            e.id,
            e.ext.as_deref().unwrap_or("mp4")
        ),
        Kind::Series => format!(
            "{}/series/{u}/{p}/{}.{}",
            base(acc),
            e.id,
            e.ext.as_deref().unwrap_or("mp4")
        ),
    }
}

/// Catch-up / archive URL for a past programme.
pub fn timeshift_url(acc: &Account, stream_id: &str, p: &Programme) -> String {
    let minutes = ((p.end_ts - p.start_ts) / 60).max(1);
    let st = &p.start_text;
    let start = if st.len() >= 16 {
        format!("{}:{}-{}", &st[..10], &st[11..13], &st[14..16])
    } else {
        st.clone()
    };
    format!(
        "{}/timeshift/{}/{}/{minutes}/{start}/{stream_id}.ts",
        base(acc),
        enc(&acc.user),
        enc(&acc.pass)
    )
}

/// Route a provider URL through the rustTV server.
pub fn proxied(url: &str) -> String {
    if direct() {
        return url.to_string();
    }
    format!("/api/p?u={}", enc(url))
}

/// Image URL usable on HTTPS pages.
pub fn img(u: &Option<String>) -> Option<String> {
    let u = u.as_ref()?;
    if u.starts_with("https://") || (direct() && u.starts_with("http://")) {
        Some(u.clone())
    } else if u.starts_with("http://") {
        Some(proxied(u))
    } else {
        None
    }
}

// ------------------------------------------------------------ VPN / proxy (desktop app)

/// Sends the proxy settings of this device to the built-in server (desktop app only).
pub async fn apply_proxy(s: &crate::store::Settings) -> Result<(), String> {
    let body = serde_json::json!({
        "kind": if s.proxy_on { s.proxy_kind.as_str() } else { "" }, "host": s.proxy_host, "port": s.proxy_port,
        "user": s.proxy_user, "pass": s.proxy_pass,
    });
    let r = Request::post("/api/upstream")
        .header("Content-Type", "application/json")
        .body(body.to_string())
        .map_err(|e| e.to_string())?
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if r.ok() {
        Ok(())
    } else {
        Err(r.text().await.unwrap_or_default())
    }
}

/// Public IP address the provider sees. Ok((ip, proxy)) or Err(message).
pub async fn test_proxy() -> Result<(String, Option<String>), String> {
    let r = Request::post("/api/upstream/test")
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let v: Value = r.json().await.map_err(|e| e.to_string())?;
    if v["ok"].as_bool() == Some(true) {
        Ok((
            v["ip"].as_str().unwrap_or("?").to_string(),
            v["proxy"].as_str().map(String::from),
        ))
    } else {
        Err(v["error"].as_str().unwrap_or("?").to_string())
    }
}
