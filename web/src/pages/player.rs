use crate::api::{self, Entry, Kind};
use crate::cache;
use crate::store::{self, t};
use crate::ui::{copy_text, fmt_time, input_value_i, use_ui, EntryCard, EpgDialog};
use std::rc::Rc;
use wasm_bindgen::prelude::*;
use yew::prelude::*;
use yew_router::prelude::*;

#[wasm_bindgen]
extern "C" {
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = start)]
    fn js_start(container: &web_sys::Element, video: &web_sys::HtmlVideoElement, opts: &JsValue);
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = stop)]
    fn js_stop();
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = attachGestures)]
    fn js_attach_gestures(layer: &web_sys::Element);
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = bindVideo)]
    fn js_bind_video(v: &web_sys::HtmlVideoElement);
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = setCallbacks)]
    fn js_callbacks(
        zap: &Closure<dyn Fn(i32)>,
        ended: &Closure<dyn Fn()>,
        error: &Closure<dyn Fn(String)>,
        stop: &Closure<dyn Fn()>,
    );
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = time)]
    fn js_time() -> f64;
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = duration)]
    fn js_duration() -> f64;
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = setFit)]
    fn js_fit(mode: &str);
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = pip)]
    fn js_pip();
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = fullscreen)]
    fn js_fullscreen();
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = speed)]
    fn js_speed(x: f64);
    #[wasm_bindgen(js_namespace = rustvPlayer, js_name = keepUi)]
    fn js_keep_ui(on: bool);
}

const FITS: [&str; 6] = ["contain", "cover", "fill", "none", "16:9", "4:3"];

fn fit_labels() -> [&'static str; 6] {
    [
        t("Anpassen (ganzes Bild)", "Fit (whole picture)"),
        t("Füllen (Ränder abschneiden)", "Fill (crop edges)"),
        t("Strecken (ganzer Bildschirm)", "Stretch (full screen)"),
        t("Original-Größe", "Original size"),
        "16:9",
        "4:3",
    ]
}

#[derive(Clone, PartialEq)]
struct PState {
    index: usize,
    prev: Option<usize>,
    len: usize,
    series: bool,
}

enum Act {
    Zap(i32),
    Goto(usize),
    Recall,
    Ended,
}

impl Reducible for PState {
    type Action = Act;
    fn reduce(self: Rc<Self>, a: Act) -> Rc<Self> {
        if self.len == 0 {
            return self;
        }
        let next = match a {
            Act::Zap(d) => {
                Some(((self.index as i64 + d as i64).rem_euclid(self.len as i64)) as usize)
            }
            Act::Goto(i) if i < self.len => Some(i),
            Act::Goto(_) => None,
            Act::Recall => self.prev,
            Act::Ended => (self.series && store::settings().auto_next && self.index + 1 < self.len)
                .then_some(self.index + 1),
        };
        match next {
            Some(n) if n != self.index => Rc::new(PState {
                index: n,
                prev: Some(self.index),
                ..(*self).clone()
            }),
            _ => self,
        }
    }
}

fn save_position(id: &str) {
    let (pos, dur) = (js_time(), js_duration());
    if dur <= 0.0 {
        return;
    }
    store::set_position(
        id,
        if pos > 10.0 && pos < dur * 0.95 {
            pos
        } else {
            0.0
        },
    );
}

#[function_component(PlayerPage)]
pub fn player_page() -> Html {
    let ui = use_ui();
    let nav = use_navigator().unwrap();
    let q = use_state(cache::queue);
    let kind = q.kind.unwrap_or(Kind::Live);
    let single = q.single.clone();
    let list: Rc<Vec<Entry>> = q.list.clone();
    let st = use_reducer(|| PState {
        index: q.index,
        prev: None,
        len: q.list.len(),
        series: q.kind == Some(Kind::Series),
    });
    let video = use_node_ref();
    let container = use_node_ref();
    let layer = use_node_ref();
    let epg = use_state(|| None::<String>);
    let error = use_state(|| None::<String>);
    let panel = use_state(|| false);
    let pquery = use_state(String::new);
    let sleep = use_mut_ref(|| None::<gloo_timers::callback::Timeout>);
    let fit = use_state(|| store::settings().fit.min(5));
    let s = store::settings();
    let live = single.is_none() && kind == Kind::Live;
    let current: Option<Entry> = if single.is_none() {
        list.get(st.index).cloned()
    } else {
        None
    };

    // callbacks from JS (kept alive for the lifetime of the page)
    {
        let (st, error, nav_cb) = (st.clone(), error.clone(), nav.clone());
        use_effect_with((), move |_| {
            let d1 = st.dispatcher();
            let d2 = st.dispatcher();
            let zap = Closure::<dyn Fn(i32)>::new(move |d| d1.dispatch(Act::Zap(d)));
            let ended = Closure::<dyn Fn()>::new(move || d2.dispatch(Act::Ended));
            let err = Closure::<dyn Fn(String)>::new(move |m: String| error.set(Some(m)));
            let stop = Closure::<dyn Fn()>::new(move || nav_cb.back());
            js_callbacks(&zap, &ended, &err, &stop);
            move || {
                js_stop();
                drop((zap, ended, err, stop));
            }
        });
    }
    {
        let (video, layer) = (video.clone(), layer.clone());
        use_effect_with((), move |_| {
            if let Some(v) = video.cast::<web_sys::HtmlVideoElement>() {
                js_bind_video(&v);
            }
            if let Some(l) = layer.cast::<web_sys::Element>() {
                js_attach_gestures(&l);
            }
        });
    }

    // start playback whenever the index changes
    {
        let (video, container, epg, error, single, list) = (
            video.clone(),
            container.clone(),
            epg.clone(),
            error.clone(),
            single.clone(),
            list.clone(),
        );
        let can_zap = list.len() > 1;
        use_effect_with(st.index, move |&index| {
            error.set(None);
            epg.set(None);
            let acc = store::account();
            let s = store::settings();
            let entry = list.get(index).cloned();
            let gest = serde_json::json!({ "bright": s.gestures && s.g_bright, "volume": s.gestures && s.g_volume,
                                           "seek": s.gestures && s.g_seek, "zap": s.gestures && s.g_zap, "pinch": s.g_pinch });
            let opts = match (&single, &entry, &acc) {
                (Some((url, _)), _, _) => {
                    serde_json::json!({ "live": false, "tsFile": true, "fileUrl": api::proxied(url), "gestures": gest })
                }
                (None, Some(e), Some(acc)) if kind == Kind::Live => serde_json::json!({
                    "live": true, "mode": s.live_format, "canZap": can_zap, "gestures": gest,
                    "tsUrl": api::proxied(&api::stream_url(acc, kind, e, "ts")),
                    "hlsUrl": api::proxied(&api::stream_url(acc, kind, e, "m3u8")),
                }),
                (None, Some(e), Some(acc)) => serde_json::json!({
                    "live": false, "gestures": gest,
                    "fileUrl": api::proxied(&api::stream_url(acc, kind, e, "")),
                    "startAt": if s.resume { store::position(&e.id) } else { 0.0 },
                }),
                _ => serde_json::Value::Null,
            };
            if let (Some(c), Some(v)) = (
                container.cast::<web_sys::Element>(),
                video.cast::<web_sys::HtmlVideoElement>(),
            ) {
                if !opts.is_null() {
                    let o = js_sys::JSON::parse(&opts.to_string()).unwrap_or(JsValue::NULL);
                    js_start(&c, &v, &o);
                }
            }
            if let Some(e) = &entry {
                store::add_history(kind, &e.id);
                if kind == Kind::Live {
                    store::set_last_live(&e.id);
                    if let Some(acc) = acc.clone() {
                        let (id, epg) = (e.id.clone(), epg.clone());
                        wasm_bindgen_futures::spawn_local(async move {
                            let l = api::short_epg(&acc, &id, 2).await;
                            if !l.is_empty() {
                                epg.set(Some(
                                    l.iter()
                                        .filter(|p| p.end_ts as f64 > js_sys::Date::now() / 1000.0)
                                        .take(2)
                                        .enumerate()
                                        .map(|(i, p)| {
                                            format!(
                                                "{} {}  ({}–{})",
                                                if i == 0 {
                                                    t("Jetzt:", "Now:")
                                                } else {
                                                    t("Danach:", "Next:")
                                                },
                                                p.title,
                                                fmt_time(p.start_ts, false),
                                                fmt_time(p.end_ts, false)
                                            )
                                        })
                                        .collect::<Vec<_>>()
                                        .join("\n"),
                                ));
                            }
                        });
                    }
                }
            }
            let id = entry.map(|e| e.id);
            move || {
                if kind != Kind::Live && single.is_none() {
                    if let Some(id) = id {
                        save_position(&id);
                    }
                }
            }
        });
    }
    // save resume position regularly
    {
        let cur = current.as_ref().map(|e| e.id.clone());
        use_effect_with(cur, move |cur| {
            let cur = cur.clone();
            let iv = (kind != Kind::Live).then(|| {
                gloo_timers::callback::Interval::new(10_000, move || {
                    if let Some(id) = &cur {
                        save_position(id);
                    }
                })
            });
            move || drop(iv)
        });
    }
    {
        let f = *fit;
        use_effect_with(f, move |f| {
            js_fit(FITS[*f as usize]);
        });
    }
    {
        let p = *panel;
        use_effect_with(p, move |p| {
            js_keep_ui(*p);
        });
    }

    let title = match (&single, &current) {
        (Some((_, t)), _) => t.clone(),
        (None, Some(e)) => {
            if list.len() > 1 {
                format!("{}   ({}/{})", e.name, st.index + 1, list.len())
            } else {
                e.name.clone()
            }
        }
        _ => String::new(),
    };
    if single.is_none() && current.is_none() {
        return html! { <div class="page center"><p>{ t("Nichts zum Abspielen.", "Nothing to play.") }</p>
        <button onclick={ let n = nav.clone(); move |_| n.back() }>{ t("Zurück", "Back") }</button></div> };
    }

    let back = {
        let n = nav.clone();
        Callback::from(move |_: MouseEvent| n.back())
    };
    let zap = |d: i32| {
        let s = st.clone();
        Callback::from(move |_: MouseEvent| s.dispatch(Act::Zap(d)))
    };
    let recall = {
        let (s, ui) = (st.clone(), ui.clone());
        Callback::from(move |_: MouseEvent| {
            if s.prev.is_some() {
                s.dispatch(Act::Recall)
            } else {
                ui.toast(t("Noch kein vorheriger Sender", "No previous channel yet"))
            }
        })
    };
    let cycle_fit = {
        let (fit, ui) = (fit.clone(), ui.clone());
        Callback::from(move |_: MouseEvent| {
            let items = fit_labels()
                .iter()
                .enumerate()
                .map(|(i, l)| {
                    let (fit, ui2, l) = (fit.clone(), ui.clone(), *l);
                    let mark = if i as u8 == *fit { "✓ " } else { "" };
                    (
                        format!("{mark}{l}"),
                        Callback::from(move |_| {
                            fit.set(i as u8);
                            js_fit(FITS[i]); // also resets the two-finger zoom
                            let mut s = store::settings();
                            s.fit = i as u8;
                            store::save_settings(&s);
                            ui2.toast(l);
                        }),
                    )
                })
                .collect();
            ui.menu(t("Bildformat", "Picture format"), items);
        })
    };
    let sleep_menu = {
        let (ui, nav, sleep) = (ui.clone(), nav.clone(), sleep.clone());
        Callback::from(move |_: MouseEvent| {
            let items = [0u32, 15, 30, 45, 60, 90, 120]
                .iter()
                .map(|&m| {
                    let (nav, sleep, ui2) = (nav.clone(), sleep.clone(), ui.clone());
                    (
                        if m == 0 {
                            t("Aus", "Off").to_string()
                        } else {
                            format!("{m} {}", t("Minuten", "minutes"))
                        },
                        Callback::from(move |_| {
                            let nav = nav.clone();
                            *sleep.borrow_mut() = (m > 0).then(|| {
                                gloo_timers::callback::Timeout::new(m * 60_000, move || nav.back())
                            });
                            ui2.toast(if m == 0 {
                                t("Sleep-Timer aus", "Sleep timer off").to_string()
                            } else {
                                format!("⏾ {m} min")
                            });
                        }),
                    )
                })
                .collect();
            ui.menu(t("Sleep-Timer", "Sleep timer"), items);
        })
    };
    let speed_menu = {
        let ui = ui.clone();
        Callback::from(move |_: MouseEvent| {
            let items = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]
                .iter()
                .map(|&x| (format!("{x}x"), Callback::from(move |_| js_speed(x))))
                .collect();
            ui.menu(t("Tempo", "Speed"), items);
        })
    };
    let epg_btn = {
        let (ui, cur) = (ui.clone(), current.clone());
        Callback::from(move |_: MouseEvent| {
            if let Some(e) = cur.clone() {
                ui.show(html! { <EpgDialog entry={e} /> });
            }
        })
    };
    let copy = {
        let (ui, cur, single) = (ui.clone(), current.clone(), single.clone());
        Callback::from(move |_: MouseEvent| {
            let url = match (&single, &cur, store::account()) {
                (Some((u, _)), _, _) => Some(u.clone()),
                (None, Some(e), Some(acc)) => Some(api::stream_url(&acc, kind, e, "ts")),
                _ => None,
            };
            if let Some(u) = url {
                copy_text(&u);
                ui.toast(t(
                    "Stream-Link kopiert – z. B. in VLC einfügen",
                    "Stream link copied – paste it into VLC",
                ));
            }
        })
    };
    let toggle_panel = {
        let p = panel.clone();
        Callback::from(move |_: MouseEvent| p.set(!*p))
    };

    // channel list panel
    let pq = pquery.trim().to_lowercase();
    let panel_html = if *panel {
        let goto = {
            let (s, p) = (st.clone(), panel.clone());
            let list = list.clone();
            Callback::from(move |e: Entry| {
                if let Some(i) = list.iter().position(|x| x.id == e.id) {
                    s.dispatch(Act::Goto(i));
                }
                p.set(false);
            })
        };
        html! {
            <aside class="panel" onclick={|e: MouseEvent| e.stop_propagation()}>
                <div class="panel-head">
                    <input type="search" placeholder={t("Suchen …", "Search …")} value={(*pquery).clone()}
                        oninput={ let q = pquery.clone(); move |e: InputEvent| q.set(input_value_i(&e)) } />
                    <button class="icon" onclick={ let p = panel.clone(); move |_| p.set(false) }>{"✕"}</button>
                </div>
                <div class="list">
                { for list.iter().filter(|e| pq.is_empty() || e.name.to_lowercase().contains(&pq)).take(400).cloned().map(|e| {
                    let active = current.as_ref().map(|c| c.id == e.id).unwrap_or(false);
                    html!{ <EntryCard entry={e} logo={s.show_logos} {active} onclick={goto.clone()} /> }
                }) }
                </div>
            </aside>
        }
    } else {
        html! {}
    };

    let multi_btn = {
        let (nav, cur) = (nav.clone(), current.clone());
        Callback::from(move |_: MouseEvent| {
            cache::set_multi_first(cur.clone());
            nav.push(&crate::Route::Multi);
        })
    };
    let multi = single.is_none() && list.len() > 1;
    html! {
        <div class="player ui" ref={container}>
            <video ref={video} playsinline=true autoplay=true></video>
            <div class="gest" ref={layer}></div>
            <div class="ov">
                <div class="ov-top">
                    <button class="icon" onclick={back}>{"✕"}</button>
                    <div class="ov-title">
                        <div class="title">{ title }</div>
                        if let Some(e) = &*epg { <div class="epg">{ e }</div> }
                    </div>
                </div>
                <div class="ov-buttons">
                    if multi {
                        <button onclick={toggle_panel}>{ if live { t("☰ Sender", "☰ Channels") } else { t("☰ Liste", "☰ List") } }</button>
                        <button onclick={zap(-1)}>{ t("◀ Zurück", "◀ Prev") }</button>
                        <button onclick={zap(1)}>{ t("Weiter ▶", "Next ▶") }</button>
                    }
                    if live && multi { <button onclick={recall}>{ t("↺ Letzter", "↺ Last") }</button> }
                    if live { <button onclick={epg_btn}>{ t("📋 Programm", "📋 Guide") }</button> }
                    if live { <button onclick={multi_btn}>{ t("▦ Multi-View", "▦ Multi-view") }</button> }
                    <button onclick={cycle_fit}>{ t("⤢ Format", "⤢ Aspect") }</button>
                    if !live { <button onclick={speed_menu}>{ t("⏩ Tempo", "⏩ Speed") }</button> }
                    <button onclick={sleep_menu}>{ t("⏾ Timer", "⏾ Sleep") }</button>
                    <button onclick={|_| js_pip()}>{ t("⧉ Bild-in-Bild", "⧉ PiP") }</button>
                    <button onclick={|_| js_fullscreen()}>{ t("⛶ Vollbild", "⛶ Fullscreen") }</button>
                    <button onclick={copy}>{ t("🔗 Link", "🔗 Link") }</button>
                </div>
            </div>
            if let Some(err) = &*error {
                <div class="perr">
                    <p>{ t("Wiedergabe-Fehler", "Playback error") }{": "}{ err }</p>
                    <p class="small">{ t("Tipp: In den Einstellungen das Live-Format ändern oder den Link in VLC öffnen.", "Tip: change the live format in settings or open the link in VLC.") }</p>
                    <div class="actions">
                        if multi { <button onclick={zap(1)}>{ t("Nächster", "Next") }</button> }
                        <button onclick={ let e = error.clone(); move |_| e.set(None) }>{ t("Schließen", "Close") }</button>
                    </div>
                </div>
            }
            { panel_html }
        </div>
    }
}
