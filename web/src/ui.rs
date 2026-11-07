//! Shared UI pieces: dialogs, toast, PIN prompt, entry card, EPG dialog.

use crate::api::{self, Entry, Kind};
use crate::cache;
use crate::store::{self, t};
use crate::Route;
use wasm_bindgen::JsCast;
use web_sys::HtmlInputElement;
use yew::prelude::*;
use yew_router::prelude::*;

#[derive(Clone, PartialEq)]
pub struct Ui {
    pub dialog: UseStateHandle<Option<Html>>,
    pub toast: UseStateHandle<Option<String>>,
}

impl Ui {
    pub fn show(&self, h: Html) {
        self.dialog.set(Some(h));
    }
    pub fn close(&self) {
        self.dialog.set(None);
    }
    pub fn toast(&self, s: impl Into<String>) {
        self.toast.set(Some(s.into()));
    }
    pub fn message(&self, title: &str, text: &str) {
        let ui = self.clone();
        self.show(html! {
            <Modal title={title.to_string()}>
                <p class="pre">{ text }</p>
                <div class="actions"><button onclick={move |_| ui.close()}>{"OK"}</button></div>
            </Modal>
        });
    }
    /// List of options; tapping one closes the dialog and runs it.
    pub fn menu(&self, title: &str, items: Vec<(String, Callback<()>)>) {
        let ui = self.clone();
        let close = ui.clone();
        self.show(html! {
            <Modal title={title.to_string()}>
                <div class="menu">
                { for items.into_iter().map(|(label, cb)| {
                    let ui = ui.clone();
                    html!{ <button class="menu-item" onclick={move |_| { ui.close(); cb.emit(()); }}>{ label }</button> }
                }) }
                </div>
                <div class="actions"><button class="ghost" onclick={move |_| close.close()}>{ t("Abbrechen", "Cancel") }</button></div>
            </Modal>
        });
    }
    /// Ask for the PIN (or create one). `unlock` = also unlock locked categories for this session.
    pub fn pin(&self, unlock: bool, on_ok: Callback<()>) {
        self.show(html! { <PinDialog {unlock} {on_ok} /> });
    }
}

#[hook]
pub fn use_ui() -> Ui {
    use_context::<Ui>().expect("ui context")
}

pub fn input_value(e: &Event) -> String {
    e.target()
        .and_then(|t| t.dyn_into::<HtmlInputElement>().ok())
        .map(|i| i.value())
        .unwrap_or_default()
}
pub fn input_value_i(e: &InputEvent) -> String {
    e.target()
        .and_then(|t| t.dyn_into::<HtmlInputElement>().ok())
        .map(|i| i.value())
        .unwrap_or_default()
}

// ------------------------------------------------------------ modal

#[derive(Properties, PartialEq)]
pub struct ModalProps {
    pub title: String,
    pub children: Children,
}

#[function_component(Modal)]
pub fn modal(p: &ModalProps) -> Html {
    let ui = use_ui();
    let close = Callback::from(move |_| ui.close());
    html! {
        <div class="modal-bg" onclick={close}>
            <div class="modal" onclick={|e: MouseEvent| e.stop_propagation()}>
                <h3>{ &p.title }</h3>
                { p.children.clone() }
            </div>
        </div>
    }
}

// ------------------------------------------------------------ PIN

#[derive(Properties, PartialEq)]
pub struct PinProps {
    pub unlock: bool,
    pub on_ok: Callback<()>,
}

#[function_component(PinDialog)]
pub fn pin_dialog(p: &PinProps) -> Html {
    let ui = use_ui();
    let a = use_state(String::new);
    let b = use_state(String::new);
    let err = use_state(|| None::<&'static str>);
    let creating = store::settings().pin.is_empty();

    let submit = {
        let (a, b, err, ui, on_ok, unlock) = (
            a.clone(),
            b.clone(),
            err.clone(),
            ui.clone(),
            p.on_ok.clone(),
            p.unlock,
        );
        Callback::from(move |e: SubmitEvent| {
            e.prevent_default();
            let mut s = store::settings();
            if creating {
                if a.len() < 4 || !a.chars().all(|c| c.is_ascii_digit()) {
                    err.set(Some(t("Mindestens 4 Ziffern", "At least 4 digits")));
                    return;
                }
                if *a != *b {
                    err.set(Some(t("PINs stimmen nicht überein", "PINs do not match")));
                    return;
                }
                s.pin = (*a).clone();
                store::save_settings(&s);
            } else if *a != s.pin {
                err.set(Some(t("Falsche PIN", "Wrong PIN")));
                return;
            }
            if unlock {
                store::set_unlocked(true);
            }
            ui.close();
            on_ok.emit(());
        })
    };
    let close = ui.clone();
    html! {
        <Modal title={ if creating { t("PIN festlegen", "Create PIN") } else { t("PIN eingeben", "Enter PIN") }.to_string() }>
            <form onsubmit={submit}>
                <input type="password" inputmode="numeric" autofocus=true placeholder={ if creating { t("Neue PIN (mind. 4 Ziffern)", "New PIN (min. 4 digits)") } else { "PIN" } }
                    oninput={ let a = a.clone(); move |e: InputEvent| a.set(input_value_i(&e)) } />
                if creating {
                    <input type="password" inputmode="numeric" placeholder={ t("PIN wiederholen", "Repeat PIN") }
                        oninput={ let b = b.clone(); move |e: InputEvent| b.set(input_value_i(&e)) } />
                }
                if let Some(e) = *err { <p class="error">{ e }</p> }
                <div class="actions">
                    <button type="button" class="ghost" onclick={move |_| close.close()}>{ t("Abbrechen", "Cancel") }</button>
                    <button type="submit">{"OK"}</button>
                </div>
            </form>
        </Modal>
    }
}

// ------------------------------------------------------------ entry card

#[derive(Properties, PartialEq)]
pub struct CardProps {
    pub entry: Entry,
    #[prop_or_default]
    pub grid: bool,
    #[prop_or_default]
    pub square: bool,
    #[prop_or_default]
    pub fav: Option<bool>,
    #[prop_or(true)]
    pub logo: bool,
    #[prop_or_default]
    pub active: bool,
    pub onclick: Callback<Entry>,
    #[prop_or_default]
    pub onmenu: Option<Callback<Entry>>,
    #[prop_or_default]
    pub onfav: Option<Callback<Entry>>,
}

#[function_component(EntryCard)]
pub fn entry_card(p: &CardProps) -> Html {
    let e = p.entry.clone();
    let click = {
        let (cb, e) = (p.onclick.clone(), e.clone());
        Callback::from(move |_| cb.emit(e.clone()))
    };
    let ctx = {
        let (cb, e) = (p.onmenu.clone(), e.clone());
        Callback::from(move |ev: MouseEvent| {
            if let Some(cb) = &cb {
                ev.prevent_default();
                cb.emit(e.clone());
            }
        })
    };
    let fav_btn = match (p.fav, &p.onfav) {
        (Some(f), Some(cb)) => {
            let (cb, e) = (cb.clone(), e.clone());
            html! { <button class={classes!("fav", f.then_some("on"))} title="★"
            onclick={move |ev: MouseEvent| { ev.stop_propagation(); cb.emit(e.clone()); }}>{ if f { "★" } else { "☆" } }</button> }
        }
        _ => html! {},
    };
    let menu_btn = match &p.onmenu {
        Some(cb) => {
            let (cb, e) = (cb.clone(), e.clone());
            html! { <button class="more" title="⋮" onclick={move |ev: MouseEvent| { ev.stop_propagation(); cb.emit(e.clone()); }}>{"⋮"}</button> }
        }
        None => html! {},
    };
    let src = if p.logo { api::img(&e.icon) } else { None };
    let sub = e.sub.clone().unwrap_or_default();
    let sub = if e.archive { format!("⏪ {sub}") } else { sub };
    if p.grid {
        html! {
            <div class={classes!("card", "poster", p.square.then_some("square"), p.active.then_some("active"))} onclick={click} oncontextmenu={ctx}>
                <div class="img">
                    if let Some(s) = src { <img loading="lazy" src={s} alt="" /> } else { <span class="noimg">{"🎬"}</span> }
                    { fav_btn }{ menu_btn }
                </div>
                <div class="name">{ &e.name }</div>
                if !sub.is_empty() { <div class="sub">{ sub }</div> }
            </div>
        }
    } else {
        html! {
            <div class={classes!("card", "row", p.active.then_some("active"))} onclick={click} oncontextmenu={ctx}>
                if p.logo {
                    <div class="logo">
                        if let Some(s) = src { <img loading="lazy" src={s} alt="" /> } else { <span>{"▶"}</span> }
                    </div>
                }
                <div class="txt">
                    <div class="name">{ &e.name }</div>
                    if !sub.is_empty() { <div class="sub">{ sub }</div> }
                </div>
                { fav_btn }{ menu_btn }
            </div>
        }
    }
}

// ------------------------------------------------------------ EPG / catch-up

#[derive(Properties, PartialEq)]
pub struct EpgProps {
    pub entry: Entry,
}

pub fn fmt_time(ts: i64, with_day: bool) -> String {
    let d = js_sys::Date::new(&((ts as f64) * 1000.0).into());
    let hm = format!("{:02}:{:02}", d.get_hours(), d.get_minutes());
    if with_day {
        let days = if store::is_de() {
            ["So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"]
        } else {
            ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
        };
        format!("{} {hm}", days[d.get_day() as usize % 7])
    } else {
        hm
    }
}

#[function_component(EpgDialog)]
pub fn epg_dialog(p: &EpgProps) -> Html {
    let ui = use_ui();
    let nav = use_navigator();
    let list = use_state(|| None::<Vec<api::Programme>>);
    let sel = use_state(|| None::<api::Programme>);
    {
        let (list, id) = (list.clone(), p.entry.id.clone());
        use_effect_with(id.clone(), move |_| {
            wasm_bindgen_futures::spawn_local(async move {
                if let Some(acc) = store::account() {
                    list.set(Some(api::full_epg(&acc, &id).await));
                }
            });
        });
    }
    let now = (js_sys::Date::now() / 1000.0) as i64;
    let close = {
        let ui = ui.clone();
        move |_| ui.close()
    };
    let body = if let Some(pr) = (*sel).clone() {
        let playable = (pr.end_ts <= now && pr.has_archive)
            || (pr.start_ts <= now && pr.end_ts > now && p.entry.archive);
        let play = {
            let (ui, nav, e, pr) = (ui.clone(), nav.clone(), p.entry.clone(), pr.clone());
            Callback::from(move |_| {
                if let Some(acc) = store::account() {
                    cache::set_single(
                        api::timeshift_url(&acc, &e.id, &pr),
                        format!("{}: {}", e.name, pr.title),
                    );
                    ui.close();
                    if let Some(n) = &nav {
                        n.push(&Route::Play);
                    }
                }
            })
        };
        let back = {
            let sel = sel.clone();
            move |_| sel.set(None)
        };
        html! {<>
            <p><b>{ &pr.title }</b><br/><small>{ format!("{} – {}", fmt_time(pr.start_ts, true), fmt_time(pr.end_ts, false)) }</small></p>
            <p class="pre">{ if pr.desc.is_empty() { t("Keine Beschreibung.", "No description.").to_string() } else { pr.desc.clone() } }</p>
            <div class="actions">
                <button class="ghost" onclick={back}>{ t("Zurück", "Back") }</button>
                if playable { <button onclick={play}>{ if pr.end_ts <= now { t("⏪ Aus Archiv abspielen", "⏪ Play from archive") } else { t("⏮ Von Anfang an", "⏮ From the start") } }</button> }
            </div>
        </>}
    } else {
        match &*list {
            None => html! { <p>{ t("Programm wird geladen …", "Loading guide …") }</p> },
            Some(l) if l.is_empty() => {
                html! { <p>{ t("Kein Programm verfügbar.", "No guide available.") }</p> }
            }
            Some(l) => html! {
                <div class="menu epg">
                { for l.iter().filter(|x| x.end_ts > now || x.has_archive).map(|x| {
                    let mark = if x.start_ts <= now && x.end_ts > now { "▶ " } else if x.end_ts <= now { "⏪ " } else { "" };
                    let (sel, x2) = (sel.clone(), x.clone());
                    html!{ <button class={classes!("menu-item", (x.start_ts <= now && x.end_ts > now).then_some("now"))}
                        onclick={move |_| sel.set(Some(x2.clone()))}>
                        { format!("{mark}{}  {}", fmt_time(x.start_ts, true), x.title) }</button> }
                }) }
                </div>
            },
        }
    };
    html! {
        <Modal title={format!("{} – {}", p.entry.name, t("Programm", "Guide"))}>
            { body }
            <div class="actions"><button class="ghost" onclick={close}>{ t("Schließen", "Close") }</button></div>
        </Modal>
    }
}

// ------------------------------------------------------------ shared item menu

/// Long-press / ⋮ menu for a list entry.
pub fn item_menu(
    ui: &Ui,
    nav: &Navigator,
    kind: Kind,
    e: &Entry,
    list: Vec<Entry>,
    refresh: Callback<()>,
) {
    let mut items: Vec<(String, Callback<()>)> = vec![];
    let idx = list.iter().position(|x| x.id == e.id).unwrap_or(0);
    if kind == Kind::Series {
        let (nav, id) = (nav.clone(), e.id.clone());
        items.push((
            t("Folgen anzeigen", "Show episodes").into(),
            Callback::from(move |_| nav.push(&Route::Series { id: id.clone() })),
        ));
    } else {
        let (nav, l) = (nav.clone(), list.clone());
        items.push((
            t("▶ Abspielen", "▶ Play").into(),
            Callback::from(move |_| {
                cache::set_queue(kind, l.clone(), idx);
                nav.push(&Route::Play);
            }),
        ));
        let (ui2, e2) = (ui.clone(), e.clone());
        items.push((
            t(
                "🔗 Stream-Link kopieren (für VLC)",
                "🔗 Copy stream link (for VLC)",
            )
            .into(),
            Callback::from(move |_| {
                if let Some(acc) = store::account() {
                    copy_text(&api::stream_url(&acc, kind, &e2, "ts"));
                    ui2.toast(t("Link kopiert", "Link copied"));
                }
            }),
        ));
    }
    if kind == Kind::Live {
        let (ui2, e2) = (ui.clone(), e.clone());
        items.push((
            if e.archive {
                t("📋 TV-Programm / Archiv", "📋 Guide / catch-up")
            } else {
                t("📋 TV-Programm", "📋 Guide")
            }
            .into(),
            Callback::from(move |_| ui2.show(html! { <EpgDialog entry={e2.clone()} /> })),
        ));
    }
    if kind == Kind::Movie && store::position(&e.id) > 0.0 {
        let (id, r) = (e.id.clone(), refresh.clone());
        items.push((
            t("Fortsetzen-Position löschen", "Clear resume position").into(),
            Callback::from(move |_| {
                store::set_position(&id, 0.0);
                r.emit(());
            }),
        ));
    }
    let fav = store::favorites(kind).contains(&e.id);
    {
        let (id, r) = (e.id.clone(), refresh.clone());
        items.push((
            if fav {
                t("★ Aus Favoriten entfernen", "★ Remove from favorites")
            } else {
                t("☆ Zu Favoriten", "☆ Add to favorites")
            }
            .into(),
            Callback::from(move |_| {
                store::toggle_favorite(kind, &id);
                r.emit(());
            }),
        ));
    }
    let in_vault = store::vault_items(kind).contains(&e.id);
    {
        let (id, r, ui2) = (e.id.clone(), refresh, ui.clone());
        items.push((
            if in_vault {
                t("🔐 Aus Tresor entfernen", "🔐 Remove from vault")
            } else {
                t("🔐 In Tresor verschieben", "🔐 Move to vault")
            }
            .into(),
            Callback::from(move |_| {
                let (id, r) = (id.clone(), r.clone());
                ui2.pin(
                    false,
                    Callback::from(move |_| {
                        store::toggle_vault_item(kind, &id);
                        r.emit(());
                    }),
                );
            }),
        ));
    }
    ui.menu(&e.name, items);
}

pub fn copy_text(s: &str) {
    if let Some(w) = web_sys::window() {
        let _ = w.navigator().clipboard().write_text(s);
    }
}
