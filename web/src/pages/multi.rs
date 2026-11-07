//! Multi-view: up to 4 live channels at once. Sound comes from the selected window.

use crate::api::{self, Entry, Kind};
use crate::cache;
use crate::store::{self, t};
use crate::ui::{use_ui, PickDialog};
use crate::Route;
use std::rc::Rc;
use wasm_bindgen::prelude::*;
use yew::prelude::*;
use yew_router::prelude::*;

#[wasm_bindgen]
extern "C" {
    #[wasm_bindgen(js_namespace = rustvMulti, js_name = start)]
    fn multi_start(
        i: u32,
        v: &web_sys::HtmlVideoElement,
        ts: &str,
        hls: &str,
        mode: &str,
        on_error: &JsValue,
    );
    #[wasm_bindgen(js_namespace = rustvMulti, js_name = stop)]
    fn multi_stop(i: u32);
    #[wasm_bindgen(js_namespace = rustvMulti, js_name = stopAll)]
    fn multi_stop_all();
}

#[function_component(MultiPage)]
pub fn multi_page() -> Html {
    let ui = use_ui();
    let nav = use_navigator().unwrap();
    let slots = use_state(|| {
        let mut v: Vec<Option<Entry>> = vec![None; 4];
        v[0] = cache::take_multi_first();
        v
    });
    let audio = use_state(|| 0usize);
    let errors = use_state(|| vec![None::<String>; 4]);
    let (v0, v1, v2, v3) = (
        use_node_ref(),
        use_node_ref(),
        use_node_ref(),
        use_node_ref(),
    );
    let videos: Vec<NodeRef> = vec![v0, v1, v2, v3];
    let started = use_mut_ref(|| vec![None::<String>; 4]);
    let loaded = use_state(|| cache::get(Kind::Live).is_some());
    let err_cbs = use_mut_ref(Vec::<Closure<dyn Fn(String)>>::new);

    {
        let loaded = loaded.clone();
        use_effect_with((), move |_| {
            wasm_bindgen_futures::spawn_local(async move {
                let _ = cache::ensure(Kind::Live).await;
                loaded.set(true);
            });
            || multi_stop_all()
        });
    }

    // start / stop streams when a window's channel changes
    {
        let (videos, started, errors, err_cbs) = (
            videos.clone(),
            started.clone(),
            errors.clone(),
            err_cbs.clone(),
        );
        use_effect_with((*slots).clone(), move |slots| {
            let acc = store::account();
            let mode = store::settings().live_format;
            for (i, slot) in slots.iter().enumerate() {
                let id = slot.as_ref().map(|e| e.id.clone());
                if started.borrow()[i] == id {
                    continue;
                }
                started.borrow_mut()[i] = id;
                match (slot, &acc, videos[i].cast::<web_sys::HtmlVideoElement>()) {
                    (Some(e), Some(acc), Some(v)) => {
                        let ts = api::proxied(&api::stream_url(acc, Kind::Live, e, "ts"));
                        let hls = api::proxied(&api::stream_url(acc, Kind::Live, e, "m3u8"));
                        let errors = errors.clone();
                        let cb = Closure::<dyn Fn(String)>::new(move |m: String| {
                            let mut v = (*errors).clone();
                            v[i] = Some(m);
                            errors.set(v);
                        });
                        multi_start(i as u32, &v, &ts, &hls, &mode, cb.as_ref());
                        err_cbs.borrow_mut().push(cb);
                    }
                    _ => multi_stop(i as u32),
                }
            }
        });
    }
    // only the selected window plays sound
    {
        let videos = videos.clone();
        use_effect_with((*audio, (*slots).clone()), move |(a, _)| {
            for (i, r) in videos.iter().enumerate() {
                if let Some(v) = r.cast::<web_sys::HtmlVideoElement>() {
                    v.set_muted(i != *a);
                }
            }
        });
    }

    let channels: Rc<Vec<Entry>> = Rc::new({
        let favs = store::favorites(Kind::Live);
        let mut l = cache::normal_entries(Kind::Live);
        l.sort_by_key(|e| (!favs.contains(&e.id), e.name.to_lowercase()));
        l
    });
    let pick = |i: usize| {
        let (ui, slots, channels, errors) =
            (ui.clone(), slots.clone(), channels.clone(), errors.clone());
        Callback::from(move |_: MouseEvent| {
            let (slots, errors) = (slots.clone(), errors.clone());
            ui.show(html! {
                <PickDialog title={format!("{} {}", t("Sender für Fenster", "Channel for window"), i + 1)} list={channels.clone()}
                    on_pick={Callback::from(move |e: Entry| {
                        let mut v = (*slots).clone(); v[i] = Some(e); slots.set(v);
                        let mut er = (*errors).clone(); er[i] = None; errors.set(er);
                    })} />
            });
        })
    };

    html! {
        <div class="mv">
            <div class="mv-bar">
                <button class="icon" onclick={ let n = nav.clone(); move |_| n.back() }>{"✕"}</button>
                <span class="small muted">{ t("Tippen = Ton von diesem Fenster · ✎ = Sender ändern · Hinweis: jedes Fenster braucht eine eigene Verbindung beim Anbieter",
                                             "Tap = sound from this window · ✎ = change channel · Note: every window uses its own provider connection") }</span>
                if !*loaded { <span class="small">{ t(" · Lade Sender …", " · loading channels …") }</span> }
            </div>
            <div class="mv-grid">
            { for (0..4).map(|i| {
                let e = slots[i].clone();
                let select = { let a = audio.clone(); Callback::from(move |_: MouseEvent| a.set(i)) };
                let clear = { let s = slots.clone(); Callback::from(move |ev: MouseEvent| { ev.stop_propagation(); let mut v = (*s).clone(); v[i] = None; s.set(v); }) };
                let full = {
                    let (nav, e) = (nav.clone(), e.clone());
                    let channels = channels.clone();
                    Callback::from(move |ev: MouseEvent| {
                        ev.stop_propagation();
                        if let Some(e) = &e {
                            let idx = channels.iter().position(|x| x.id == e.id).unwrap_or(0);
                            cache::set_queue(Kind::Live, channels.as_ref().clone(), idx);
                            nav.push(&Route::Play);
                        }
                    })
                };
                let change = { let p = pick(i); Callback::from(move |ev: MouseEvent| { ev.stop_propagation(); p.emit(ev); }) };
                html!{
                    <div class={classes!("mv-cell", (*audio == i && e.is_some()).then_some("audio"))} onclick={ if e.is_some() { select } else { pick(i) } }>
                        <video ref={videos[i].clone()} playsinline=true autoplay=true muted={*audio != i}></video>
                        if let Some(en) = &e {
                            <div class="mv-label">{ if *audio == i { "🔊 " } else { "" } }{ &en.name }</div>
                            <div class="mv-btns">
                                <button onclick={change} title={t("Sender ändern", "Change channel")}>{"✎"}</button>
                                <button onclick={full} title={t("Vollbild", "Full screen")}>{"⛶"}</button>
                                <button onclick={clear} title={t("Leeren", "Clear")}>{"✕"}</button>
                            </div>
                            if let Some(err) = &errors[i] { <div class="mv-err">{ t("Fehler – evtl. Verbindungs-Limit des Anbieters", "Error – maybe the provider's connection limit") }<br/><small>{ err }</small></div> }
                        } else {
                            <div class="mv-empty">{ t("＋ Sender wählen", "＋ Choose channel") }</div>
                        }
                    </div>
                }
            }) }
            </div>
        </div>
    }
}
