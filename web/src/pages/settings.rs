use super::TopBar;
use crate::store::{self, t, Settings};
use crate::ui::{input_value, use_ui};
use crate::{cache, Route, ServerInfo, REPO};
use wasm_bindgen::JsCast;
use yew::prelude::*;

#[function_component(SettingsPage)]
pub fn settings_page() -> Html {
    let ui = use_ui();
    let info = use_context::<ServerInfo>().unwrap_or_default();
    let s = use_state(store::settings);

    let update = {
        let s = s.clone();
        move |f: Box<dyn Fn(&mut Settings)>| {
            let mut v = (*s).clone();
            f(&mut v);
            store::save_settings(&v);
            s.set(v);
        }
    };
    let toggle = |label: &'static str,
                  hint: &'static str,
                  get: fn(&Settings) -> bool,
                  set: fn(&mut Settings, bool),
                  disabled: bool| {
        let update = update.clone();
        let val = get(&s);
        html! {
            <label class={classes!("opt", disabled.then_some("disabled"))}>
                <div><div>{ label }</div>if !hint.is_empty() { <div class="sub">{ hint }</div> }</div>
                <input type="checkbox" checked={val} disabled={disabled} onchange={move |_| update(Box::new(move |x| set(x, !val)))} />
            </label>
        }
    };
    let select = |label: &'static str,
                  cur: String,
                  options: Vec<(&'static str, &'static str)>,
                  set: fn(&mut Settings, String)| {
        let update = update.clone();
        html! {
            <label class="opt">
                <div>{ label }</div>
                <select onchange={move |e: Event| { let v = input_value(&e); update(Box::new(move |x| set(x, v.clone()))) }}>
                { for options.into_iter().map(|(v, l)| html!{ <option value={v} selected={cur == v}>{ l }</option> }) }
                </select>
            </label>
        }
    };

    let set_pin = {
        let ui = ui.clone();
        Callback::from(move |_: MouseEvent| {
            let ui2 = ui.clone();
            let create = Callback::from(move |_| {
                let mut x = store::settings();
                x.pin.clear();
                store::save_settings(&x);
                ui2.pin(false, Callback::from(|_| {}));
            });
            if store::settings().pin.is_empty() {
                create.emit(())
            } else {
                ui.pin(false, create)
            }
        })
    };
    let lock_toggle = {
        let (ui, update) = (ui.clone(), update.clone());
        let cur = s.auto_lock_adult;
        Callback::from(move |_: Event| {
            let update = update.clone();
            let f = Callback::from(move |_| update(Box::new(move |x| x.auto_lock_adult = !cur)));
            // switching protection OFF needs the PIN
            if cur && !store::settings().pin.is_empty() {
                ui.pin(false, f)
            } else {
                f.emit(())
            }
        })
    };
    let export = Callback::from(move |_: MouseEvent| {
        let data = store::export_all();
        let parts = js_sys::Array::of1(&data.into());
        let bag = web_sys::BlobPropertyBag::new();
        bag.set_type("application/json");
        if let Ok(blob) = web_sys::Blob::new_with_str_sequence_and_options(&parts, &bag) {
            if let Ok(url) = web_sys::Url::create_object_url_with_blob(&blob) {
                let doc = web_sys::window().unwrap().document().unwrap();
                let a: web_sys::HtmlAnchorElement =
                    doc.create_element("a").unwrap().dyn_into().unwrap();
                a.set_href(&url);
                a.set_download("rustv-backup.json");
                a.click();
                let _ = web_sys::Url::revoke_object_url(&url);
            }
        }
    });
    let import = {
        let ui = ui.clone();
        Callback::from(move |e: Event| {
            let input: web_sys::HtmlInputElement = e.target_unchecked_into();
            let Some(file) = input.files().and_then(|f| f.get(0)) else {
                return;
            };
            let ui = ui.clone();
            wasm_bindgen_futures::spawn_local(async move {
                let text = wasm_bindgen_futures::JsFuture::from(file.text())
                    .await
                    .ok()
                    .and_then(|v| v.as_string())
                    .unwrap_or_default();
                match store::import_all(&text) {
                    Ok(n) => {
                        cache::clear();
                        ui.message(
                            t("Import", "Import"),
                            &format!(
                                "{n} {}",
                                t(
                                    "Einträge übernommen. Seite wird neu geladen.",
                                    "entries restored. Reloading."
                                )
                            ),
                        );
                        let _ = web_sys::window().unwrap().location().reload();
                    }
                    Err(e) => ui.message(t("Import fehlgeschlagen", "Import failed"), &e),
                }
            });
        })
    };
    let reload_lists = {
        let ui = ui.clone();
        Callback::from(move |_: MouseEvent| {
            cache::clear();
            ui.toast(t("Listen werden neu geladen", "Lists will be reloaded"));
        })
    };
    let lang_change = Callback::from(|_: ()| {
        let _ = web_sys::window().unwrap().location().reload();
    });

    let g = s.gestures;
    html! {
        <div class="page narrow">
            <TopBar title={t("Einstellungen", "Settings")} back={Some(Route::Home)} />

            <h2>{ t("Allgemein", "General") }</h2>
            { {
                let l = lang_change.clone();
                let update = update.clone();
                let cur = s.lang.clone();
                html!{ <label class="opt"><div>{ t("Sprache", "Language") }</div>
                    <select onchange={move |e: Event| { let v = input_value(&e); update(Box::new(move |x| x.lang = v.clone())); l.emit(()); }}>
                        <option value="auto" selected={cur == "auto"}>{ t("Automatisch", "Automatic") }</option>
                        <option value="de" selected={cur == "de"}>{"Deutsch"}</option>
                        <option value="en" selected={cur == "en"}>{"English"}</option>
                    </select></label> }
            } }

            <h2>{ t("Wiedergabe", "Playback") }</h2>
            { select(t("Live-TV-Format", "Live TV format"), s.live_format.clone(), vec![
                ("auto", t("Automatisch (empfohlen)", "Automatic (recommended)")),
                ("ts", t("MPEG-TS (schnell, nicht auf iPhone)", "MPEG-TS (fast, not on iPhone)")),
                ("hls", t("HLS (.m3u8, überall)", "HLS (.m3u8, everywhere)")),
            ], |x, v| x.live_format = v) }
            { toggle(t("Filme/Folgen fortsetzen", "Resume movies/episodes"), "", |x| x.resume, |x, v| x.resume = v, false) }
            { toggle(t("Nächste Folge automatisch", "Play next episode automatically"), "", |x| x.auto_next, |x, v| x.auto_next = v, false) }

            <h2>{ t("Gesten im Player", "Player gestures") }</h2>
            { toggle(t("Wisch-Gesten (alle)", "Swipe gestures (all)"), t("Hauptschalter", "Master switch"), |x| x.gestures, |x, v| x.gestures = v, false) }
            { toggle(t("Helligkeit", "Brightness"), t("Links hoch/runter wischen", "Swipe up/down on the left"), |x| x.g_bright, |x, v| x.g_bright = v, !g) }
            { toggle(t("Lautstärke", "Volume"), t("Rechts hoch/runter wischen (nicht auf iPhone)", "Swipe up/down on the right (not on iPhone)"), |x| x.g_volume, |x, v| x.g_volume = v, !g) }
            { toggle(t("Spulen", "Seek"), t("Bei Filmen seitlich wischen", "Swipe sideways in movies"), |x| x.g_seek, |x, v| x.g_seek = v, !g) }
            { toggle(t("Sender wechseln", "Switch channel"), t("Bei Live-TV seitlich wischen", "Swipe sideways in live TV"), |x| x.g_zap, |x, v| x.g_zap = v, !g) }

            { toggle(t("Zoom mit zwei Fingern", "Two-finger zoom"), t("Bild vergrößern/verkleinern; Bildformat setzt zurück", "Enlarge/shrink the picture; picture format resets it"), |x| x.g_pinch, |x, v| x.g_pinch = v, false) }

            <h2>{ t("Liste", "List") }</h2>
            { toggle(t("Filme & Serien als Bilder-Raster", "Movies & series as poster grid"), "", |x| x.poster_grid, |x, v| x.poster_grid = v, false) }
            { toggle(t("Logos/Cover anzeigen", "Show logos/covers"), "", |x| x.show_logos, |x, v| x.show_logos = v, false) }
            { toggle(t("Favoriten zuerst", "Favorites first"), "", |x| x.fav_first, |x, v| x.fav_first = v, false) }

            <h2>{ t("Jugendschutz", "Parental control") }</h2>
            <button class="opt-btn" onclick={set_pin}>{ if s.pin.is_empty() { t("PIN festlegen", "Set PIN") } else { t("PIN ändern", "Change PIN") } }</button>
            <label class="opt">
                <div><div>{ t("Erwachsenen-Kategorien automatisch sperren", "Lock adult categories automatically") }</div>
                     <div class="sub">{ t("„Adult“, „XXX“, „18+“ … brauchen die PIN", "“Adult”, “XXX”, “18+” … need the PIN") }</div></div>
                <input type="checkbox" checked={s.auto_lock_adult} onchange={lock_toggle} />
            </label>

            <h2>{ t("Daten", "Data") }</h2>
            <p class="muted small">{ t("Alles wird nur auf diesem Gerät gespeichert. Mit Export/Import nimmst du es auf ein anderes Gerät mit (enthält deine Zugangsdaten!).",
                                       "Everything is stored on this device only. Use export/import to move it to another device (contains your logins!).") }</p>
            <button class="opt-btn" onclick={export}>{ t("⬇ Export (Sicherung)", "⬇ Export (backup)") }</button>
            <label class="opt-btn file">{ t("⬆ Import", "⬆ Import") }<input type="file" accept="application/json" onchange={import} /></label>
            <button class="opt-btn" onclick={reload_lists}>{ t("↻ Senderlisten neu laden", "↻ Reload channel lists") }</button>

            <p class="muted small about">
                { format!("rustTV {} · {} · ", info.version, if info.mode == "desktop" { "Desktop" } else { "Server" }) }
                <a href={format!("https://github.com/{REPO}")} target={if info.mode == "desktop" { "_self" } else { "_blank" }}>{"GitHub"}</a>
            </p>
        </div>
    }
}
