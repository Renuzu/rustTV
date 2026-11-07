//! Everything saved on this device (browser localStorage).
//! Nothing is sent anywhere except to the user's own rustTV server.

use crate::api::Kind;
use gloo_storage::{LocalStorage, Storage};
use serde::{Deserialize, Serialize};
use std::cell::Cell;
use std::collections::{HashMap, HashSet};

thread_local! {
    static UNLOCKED: Cell<bool> = const { Cell::new(false) };
    static DE: Cell<bool> = const { Cell::new(false) };
}

// ------------------------------------------------------------ language

pub fn init_lang() {
    let s = settings();
    let de = match s.lang.as_str() {
        "de" => true,
        "en" => false,
        _ => web_sys::window()
            .and_then(|w| w.navigator().language())
            .map(|l| l.to_lowercase().starts_with("de"))
            .unwrap_or(false),
    };
    DE.with(|d| d.set(de));
}

pub fn is_de() -> bool {
    DE.with(|d| d.get())
}

/// Pick German or English text.
pub fn t(de: &'static str, en: &'static str) -> &'static str {
    if is_de() {
        de
    } else {
        en
    }
}

// ------------------------------------------------------------ session unlock (PIN)

pub fn unlocked() -> bool {
    UNLOCKED.with(|u| u.get())
}
pub fn set_unlocked(v: bool) {
    UNLOCKED.with(|u| u.set(v))
}

// ------------------------------------------------------------ settings

#[derive(Serialize, Deserialize, Clone, PartialEq, Debug)]
#[serde(default)]
pub struct Settings {
    pub lang: String,
    pub live_format: String,
    pub sort: u8,
    pub fav_first: bool,
    pub poster_grid: bool,
    pub show_logos: bool,
    pub resume: bool,
    pub auto_next: bool,
    pub gestures: bool,
    pub g_bright: bool,
    pub g_volume: bool,
    pub g_seek: bool,
    pub g_zap: bool,
    pub g_pinch: bool,
    pub auto_lock_adult: bool,
    pub pin: String,
    pub fit: u8,
    /// "" (off), "socks5" or "http"
    pub proxy_kind: String,
    pub proxy_host: String,
    pub proxy_port: u16,
    pub proxy_user: String,
    pub proxy_pass: String,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            lang: "auto".into(),
            live_format: "auto".into(),
            sort: 0,
            fav_first: true,
            poster_grid: true,
            show_logos: true,
            resume: true,
            auto_next: true,
            gestures: true,
            g_bright: true,
            g_volume: true,
            g_seek: true,
            g_zap: true,
            g_pinch: true,
            auto_lock_adult: true,
            pin: String::new(),
            fit: 0,
            proxy_kind: String::new(),
            proxy_host: String::new(),
            proxy_port: 1080,
            proxy_user: String::new(),
            proxy_pass: String::new(),
        }
    }
}

pub fn settings() -> Settings {
    LocalStorage::get("rustv.settings").unwrap_or_default()
}
pub fn save_settings(s: &Settings) {
    let _ = LocalStorage::set("rustv.settings", s);
}

// ------------------------------------------------------------ accounts

#[derive(Serialize, Deserialize, Clone, PartialEq, Debug)]
pub struct Account {
    pub id: String,
    pub name: String,
    pub server: String,
    pub user: String,
    pub pass: String,
}

pub fn accounts() -> Vec<Account> {
    LocalStorage::get("rustv.accounts").unwrap_or_default()
}
pub fn save_accounts(a: &[Account]) {
    let _ = LocalStorage::set("rustv.accounts", a);
}
pub fn active_id() -> String {
    LocalStorage::get("rustv.active").unwrap_or_default()
}
pub fn set_active(id: &str) {
    let _ = LocalStorage::set("rustv.active", id);
}
pub fn account() -> Option<Account> {
    let all = accounts();
    let id = active_id();
    all.iter()
        .find(|a| a.id == id)
        .cloned()
        .or_else(|| all.first().cloned())
}
pub fn add_account(a: Account) {
    let mut all = accounts();
    set_active(&a.id);
    all.push(a);
    save_accounts(&all);
}
pub fn remove_account(id: &str) {
    let all: Vec<Account> = accounts().into_iter().filter(|a| a.id != id).collect();
    save_accounts(&all);
    // delete this account's data
    let prefix = format!("rustv.a.{id}.");
    if let Some(st) = web_sys::window().and_then(|w| w.local_storage().ok().flatten()) {
        let n = st.length().unwrap_or(0);
        let keys: Vec<String> = (0..n)
            .filter_map(|i| st.key(i).ok().flatten())
            .filter(|k| k.starts_with(&prefix))
            .collect();
        for k in keys {
            let _ = st.remove_item(&k);
        }
    }
    if active_id() == id {
        set_active(all.first().map(|a| a.id.as_str()).unwrap_or(""));
    }
}

// ------------------------------------------------------------ per-account data

fn akey(k: &str) -> String {
    format!(
        "rustv.a.{}.{}",
        account().map(|a| a.id).unwrap_or_default(),
        k
    )
}
fn get_set(k: &str) -> HashSet<String> {
    LocalStorage::get(akey(k)).unwrap_or_default()
}
fn put_set(k: &str, v: &HashSet<String>) {
    let _ = LocalStorage::set(akey(k), v);
}
fn get_list(k: &str) -> Vec<String> {
    LocalStorage::get(akey(k)).unwrap_or_default()
}
fn put_list(k: &str, v: &[String]) {
    let _ = LocalStorage::set(akey(k), v);
}

macro_rules! set_fns {
    ($get:ident, $put:ident, $toggle:ident, $key:literal) => {
        pub fn $get(kind: Kind) -> HashSet<String> {
            get_set(&format!(concat!($key, "_{}"), kind.key()))
        }
        pub fn $put(kind: Kind, v: &HashSet<String>) {
            put_set(&format!(concat!($key, "_{}"), kind.key()), v)
        }
        pub fn $toggle(kind: Kind, id: &str) {
            let mut s = $get(kind);
            if !s.remove(id) {
                s.insert(id.to_string());
            }
            $put(kind, &s);
        }
    };
}

set_fns!(favorites, set_favorites, toggle_favorite, "fav");
set_fns!(hidden_cats, set_hidden_cats, toggle_hidden_cat, "hidden");
set_fns!(locked_cats, set_locked_cats, toggle_locked_cat, "locked");
set_fns!(vault_cats, set_vault_cats, toggle_vault_cat, "vault");
set_fns!(vault_items, set_vault_items, toggle_vault_item, "vaulti");

pub fn cat_order(kind: Kind) -> Vec<String> {
    get_list(&format!("catorder_{}", kind.key()))
}
pub fn set_cat_order(kind: Kind, v: &[String]) {
    put_list(&format!("catorder_{}", kind.key()), v)
}

pub fn history(kind: Kind) -> Vec<String> {
    get_list(&format!("hist_{}", kind.key()))
}
pub fn add_history(kind: Kind, id: &str) {
    let mut h: Vec<String> = history(kind).into_iter().filter(|x| x != id).collect();
    h.insert(0, id.to_string());
    h.truncate(50);
    put_list(&format!("hist_{}", kind.key()), &h);
}

#[allow(dead_code)]
pub fn last_live() -> String {
    LocalStorage::get(akey("last_live")).unwrap_or_default()
}
pub fn set_last_live(id: &str) {
    let _ = LocalStorage::set(akey("last_live"), id);
}

fn positions() -> HashMap<String, f64> {
    LocalStorage::get(akey("pos")).unwrap_or_default()
}
/// Saved resume position in seconds.
pub fn position(id: &str) -> f64 {
    positions().get(id).copied().unwrap_or(0.0)
}
pub fn set_position(id: &str, secs: f64) {
    let mut p = positions();
    if secs <= 0.0 {
        p.remove(id);
    } else {
        p.insert(id.to_string(), secs);
    }
    let _ = LocalStorage::set(akey("pos"), p);
}

// ------------------------------------------------------------ backup

/// All rustTV data as JSON (for moving to another device).
pub fn export_all() -> String {
    let mut map = serde_json::Map::new();
    if let Some(st) = web_sys::window().and_then(|w| w.local_storage().ok().flatten()) {
        let n = st.length().unwrap_or(0);
        for i in 0..n {
            if let Some(k) = st.key(i).ok().flatten() {
                if k.starts_with("rustv.") {
                    if let Some(v) = st.get_item(&k).ok().flatten() {
                        map.insert(k, serde_json::Value::String(v));
                    }
                }
            }
        }
    }
    serde_json::to_string_pretty(&map).unwrap_or_default()
}

pub fn import_all(json: &str) -> Result<usize, String> {
    let map: serde_json::Map<String, serde_json::Value> =
        serde_json::from_str(json).map_err(|e| e.to_string())?;
    let st = web_sys::window()
        .and_then(|w| w.local_storage().ok().flatten())
        .ok_or("no storage")?;
    let mut n = 0;
    for (k, v) in map {
        if let (true, Some(s)) = (k.starts_with("rustv."), v.as_str()) {
            let _ = st.set_item(&k, s);
            n += 1;
        }
    }
    Ok(n)
}
