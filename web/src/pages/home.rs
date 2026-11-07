use super::TopBar;
use crate::api::{self, Entry, Kind};
use crate::cache;
use crate::store::{self, t};
use crate::ui::{input_value_i, use_ui, EntryCard};
use crate::{Route, ServerInfo, REPO};
use gloo_net::http::Request;
use yew::prelude::*;
use yew_router::prelude::*;

#[function_component(HomePage)]
pub fn home_page() -> Html {
    let ui = use_ui();
    let nav = use_navigator().unwrap();
    let tick = use_state(|| 0u32);
    let errors = use_state(Vec::<(Kind, String)>::new);
    let query = use_state(String::new);

    {
        let (tick, errors) = (tick.clone(), errors.clone());
        use_effect_with((), move |_| {
            wasm_bindgen_futures::spawn_local(async move {
                let mut errs = vec![];
                for k in Kind::ALL {
                    if let Err(e) = cache::ensure(k).await {
                        errs.push((k, e));
                    }
                    tick.set(js_sys::Math::random().to_bits() as u32);
                }
                errors.set(errs);
            });
        });
    }

    let open = {
        let nav = nav.clone();
        move |k: Kind, list: Vec<Entry>, e: Entry| {
            if k == Kind::Series {
                store::add_history(Kind::Series, &e.id);
                nav.push(&Route::Series { id: e.id.clone() });
            } else {
                let idx = list.iter().position(|x| x.id == e.id).unwrap_or(0);
                cache::set_queue(k, list, idx);
                nav.push(&Route::Play);
            }
        }
    };

    let settings = store::settings();
    let tiles = Kind::ALL.iter().map(|&k| {
        let data = cache::get(k);
        let visible = cache::normal_entries(k);
        let count = match (&data, errors.iter().find(|(x, _)| *x == k)) {
            (Some(d), _) => format!("{} {}", d.entries.len(), if k == Kind::Live { t("Sender", "channels") } else { t("Titel", "titles") }),
            (None, Some(_)) => t("Fehler beim Laden", "Failed to load").to_string(),
            (None, None) => t("wird geladen …", "loading …").to_string(),
        };
        let favs = store::favorites(k).len();
        let by_id: std::collections::HashMap<&str, &Entry> = visible.iter().map(|e| (e.id.as_str(), e)).collect();
        let mut pics: Vec<String> = store::history(k).iter().filter_map(|id| by_id.get(id.as_str())).filter_map(|e| api::img(&e.icon)).collect();
        pics.extend(visible.iter().take(40).filter_map(|e| api::img(&e.icon)));
        pics.dedup();
        pics.truncate(4);
        let route = Route::List { kind: k.key().into() };
        html! {
            <Link<Route> to={route} classes={classes!("tile", format!("tile-{}", k.key()))}>
                if settings.show_logos {
                    <div class="collage">{ for pics.iter().map(|p| html!{ <img loading="lazy" src={p.clone()} alt="" /> }) }</div>
                }
                <div class="shade"></div>
                <div class="tile-body">
                    <span class="tile-icon">{ k.icon() }</span>
                    <div>
                        <div class="tile-title">{ k.title() }</div>
                        <div class="tile-sub">{ count }{ if favs > 0 { format!(" · {favs} ★") } else { String::new() } }</div>
                    </div>
                </div>
            </Link<Route>>
        }
    });

    let vault_click = {
        let (ui, nav) = (ui.clone(), nav.clone());
        Callback::from(move |_| {
            if cache::vault_empty() {
                ui.message(
                    t("🔐 Tresor ist leer", "🔐 Vault is empty"),
                    t("So legst du Inhalte hinein:\n• Bouquets verwalten → bei einer Kategorie 🔐 antippen\n• oder bei einem Sender/Film ⋮ → „In Tresor verschieben“",
                      "How to add content:\n• Manage bouquets → tap 🔐 on a category\n• or on a channel/movie ⋮ → “Move to vault”"),
                );
                return;
            }
            let nav = nav.clone();
            ui.pin(
                false,
                Callback::from(move |_| {
                    let k = Kind::ALL
                        .into_iter()
                        .find(|k| {
                            !store::vault_cats(*k).is_empty() || !store::vault_items(*k).is_empty()
                        })
                        .unwrap_or(Kind::Live);
                    nav.push(&Route::Vault {
                        kind: k.key().into(),
                    });
                }),
            );
        })
    };

    // "recently watched" rows
    let rows = Kind::ALL.iter().filter_map(|&k| {
        let vis = cache::normal_entries(k);
        let by_id: std::collections::HashMap<String, Entry> =
            vis.into_iter().map(|e| (e.id.clone(), e)).collect();
        let recent: Vec<Entry> = store::history(k)
            .iter()
            .filter_map(|id| by_id.get(id).cloned())
            .take(20)
            .map(|mut e| {
                if store::position(&e.id) > 0.0 {
                    e.sub = Some(t("▶ weiterschauen", "▶ continue").into());
                }
                e
            })
            .collect();
        if recent.is_empty() {
            return None;
        }
        let open = open.clone();
        let list = recent.clone();
        Some(html! {
            <section>
                <h2>{ format!("{} – {}", t("Zuletzt gesehen", "Recently watched"), k.title()) }</h2>
                <div class="hrow">
                { for recent.into_iter().map(|e| {
                    let (open, list) = (open.clone(), list.clone());
                    html!{ <EntryCard entry={e} grid={true} square={k == Kind::Live} logo={true}
                        onclick={Callback::from(move |e: Entry| open(k, list.clone(), e))} /> }
                }) }
                </div>
            </section>
        })
    });

    // global search
    let q = query.trim().to_lowercase();
    let results: Vec<(Kind, Entry)> = if q.chars().count() >= 2 {
        Kind::ALL
            .iter()
            .flat_map(|&k| {
                cache::normal_entries(k)
                    .into_iter()
                    .filter(|e| e.name.to_lowercase().contains(&q))
                    .take(40)
                    .map(move |e| (k, e))
            })
            .collect()
    } else {
        vec![]
    };

    let acc = store::account();
    html! {
        <div class="page">
            <TopBar title="rustTV" sub={acc.map(|a| AttrValue::from(a.name))}>
                <Link<Route> to={Route::Bouquets { kind: "live".into() }} classes="icon" ><span title={t("Bouquets", "Bouquets")}>{"📚"}</span></Link<Route>>
                <Link<Route> to={Route::Multi} classes="icon"><span title={t("Multi-View (4 Sender)", "Multi-view (4 channels)")}>{"▦"}</span></Link<Route>>
                <Link<Route> to={Route::Accounts} classes="icon"><span title={t("Konten", "Accounts")}>{"👤"}</span></Link<Route>>
                <Link<Route> to={Route::Settings} classes="icon"><span title={t("Einstellungen", "Settings")}>{"⚙️"}</span></Link<Route>>
            </TopBar>
            <UpdateBanner />
            <VpnButton />
            <div class="design-chips">
                { for store::DESIGNS.iter().enumerate().map(|(i, (de, en))| {
                    let tick = tick.clone();
                    let on = settings.design as usize == i;
                    html!{ <button class={classes!("chip", on.then_some("on"))} onclick={move |_| {
                        let mut s = store::settings();
                        s.design = i as u8;
                        store::save_settings(&s);
                        tick.set(tick.wrapping_add(1));
                    }}>{ t(de, en) }</button> }
                }) }
            </div>
            <input class="search" type="search" placeholder={ t("🔍 Überall suchen …", "🔍 Search everything …") }
                value={(*query).clone()} oninput={ let q = query.clone(); move |e: InputEvent| q.set(input_value_i(&e)) } />
            if !q.is_empty() && q.chars().count() >= 2 {
                <div class="list">
                    if results.is_empty() { <p class="muted">{ t("Nichts gefunden", "Nothing found") }</p> }
                    { for results.into_iter().map(|(k, mut e)| {
                        let open = open.clone();
                        e.sub = Some(k.title().to_string());
                        let single = vec![e.clone()];
                        html!{ <EntryCard entry={e} logo={settings.show_logos} onclick={Callback::from(move |e: Entry| open(k, single.clone(), e))} /> }
                    }) }
                </div>
            } else {
                <div class="tiles">
                    { for tiles }
                    <button class="tile tile-vault" onclick={vault_click}>
                        <div class="tile-body"><span class="tile-icon">{"🔐"}</span>
                            <div><div class="tile-title">{ t("Tresor", "Vault") }</div><div class="tile-sub">{ t("Geschützte Inhalte – PIN nötig", "Protected content – PIN required") }</div></div>
                        </div>
                    </button>
                </div>
                { for rows }
                if !errors.is_empty() {
                    <p class="error">{ for errors.iter().map(|(k, e)| html!{ <>{ format!("{}: {e}", k.title()) }<br/></> }) }</p>
                }
            }
        </div>
    }
}

/// Shows a hint when a newer release exists on GitHub.
#[function_component(UpdateBanner)]
pub fn update_banner() -> Html {
    let info = use_context::<ServerInfo>().unwrap_or_default();
    let latest = use_state(|| None::<(String, String, Option<String>)>);
    {
        let latest = latest.clone();
        use_effect_with((), move |_| {
            wasm_bindgen_futures::spawn_local(async move {
                let url = format!("https://api.github.com/repos/{REPO}/releases/latest");
                if let Ok(r) = Request::get(&url).send().await {
                    if let Ok(v) = r.json::<serde_json::Value>().await {
                        let tag = v["tag_name"]
                            .as_str()
                            .unwrap_or("")
                            .trim_start_matches('v')
                            .to_string();
                        let page = v["html_url"].as_str().unwrap_or("").to_string();
                        let installer = v["assets"]
                            .as_array()
                            .into_iter()
                            .flatten()
                            .filter_map(|a| a["browser_download_url"].as_str())
                            .find(|u| {
                                if is_mac() {
                                    u.ends_with(".dmg")
                                } else {
                                    u.ends_with("-setup.exe")
                                }
                            })
                            .map(String::from);
                        latest.set(Some((tag, page, installer)));
                    }
                }
            });
        });
    }
    let Some((tag, page, installer)) = (*latest).clone() else {
        return html! {};
    };
    if tag.is_empty() || info.version.is_empty() || !newer(&tag, &info.version) {
        return html! {};
    }
    let desktop = info.mode == "desktop";
    html! {
        <div class="banner">
            { format!("{} {tag} ", t("Neue Version verfügbar:", "New version available:")) }
            if desktop {
                <a href={installer.unwrap_or(page.clone())}>{ t("Jetzt herunterladen & installieren", "Download & install now") }</a>
            } else {
                <a href={page} target={if desktop { "_self" } else { "_blank" }}>{ t("Server aktualisieren (Anleitung)", "Update your server (how-to)") }</a>
            }
        </div>
    }
}

pub fn newer(a: &str, b: &str) -> bool {
    let p = |s: &str| {
        s.split('.')
            .map(|x| x.parse::<u32>().unwrap_or(0))
            .collect::<Vec<_>>()
    };
    p(a) > p(b)
}

pub fn is_mac() -> bool {
    web_sys::window()
        .and_then(|w| w.navigator().user_agent().ok())
        .map(|ua| ua.contains("Macintosh"))
        .unwrap_or(false)
}

/// Desktop app: switch the VPN/proxy on or off from the start screen.
#[function_component(VpnButton)]
pub fn vpn_button() -> Html {
    let info = use_context::<ServerInfo>().unwrap_or_default();
    let ui = use_ui();
    let s = use_state(store::settings);
    let busy = use_state(|| false);
    if info.mode != "desktop" || s.proxy_kind.is_empty() || s.proxy_host.trim().is_empty() {
        return html! {};
    }
    let on = s.proxy_on;
    let click = {
        let (s, busy, ui) = (s.clone(), busy.clone(), ui.clone());
        Callback::from(move |_: MouseEvent| {
            let mut v = (*s).clone();
            v.proxy_on = !v.proxy_on;
            let (s, busy, ui) = (s.clone(), busy.clone(), ui.clone());
            busy.set(true);
            wasm_bindgen_futures::spawn_local(async move {
                match api::apply_proxy(&v).await {
                    Ok(()) => {
                        store::save_settings(&v);
                        cache::clear();
                        ui.toast(if v.proxy_on {
                            t("🔒 VPN/Proxy eingeschaltet", "🔒 VPN/proxy on")
                        } else {
                            t("🔓 VPN/Proxy ausgeschaltet", "🔓 VPN/proxy off")
                        });
                        s.set(v);
                        // reload lists through the new connection
                        if let Some(w) = web_sys::window() {
                            let _ = w.location().reload();
                        }
                    }
                    Err(e) => ui.message(t("Fehler", "Error"), &e),
                }
                busy.set(false);
            });
        })
    };
    html! {
        <button class={classes!("vpn-btn", on.then_some("on"))} disabled={*busy} onclick={click}>
            { if *busy { t("⏳ Bitte warten …", "⏳ Please wait …") } else if on { t("🔒 VPN an – klicken zum Trennen", "🔒 VPN on – click to disconnect") } else { t("🔓 VPN aus – klicken zum Verbinden", "🔓 VPN off – click to connect") } }
        </button>
    }
}
