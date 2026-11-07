use crate::api::{self, Entry, Kind};
use crate::cache;
use crate::store::{self, t};
use crate::ui::{copy_text, use_ui, EntryCard};
use crate::Route;
use yew::prelude::*;
use yew_router::prelude::*;

#[derive(Properties, PartialEq)]
pub struct SeriesProps {
    pub id: String,
}

fn fmt_secs(s: f64) -> String {
    let s = s as u64;
    if s >= 3600 {
        format!("{}:{:02}:{:02}", s / 3600, s / 60 % 60, s % 60)
    } else {
        format!("{}:{:02}", s / 60, s % 60)
    }
}

#[function_component(SeriesPage)]
pub fn series_page(p: &SeriesProps) -> Html {
    let ui = use_ui();
    let nav = use_navigator().unwrap();
    let episodes = use_state(|| None::<Result<Vec<Entry>, String>>);
    let season = use_state(|| None::<String>);
    let tick = use_state(|| 0u32);

    {
        let (episodes, id) = (episodes.clone(), p.id.clone());
        use_effect_with(id.clone(), move |_| {
            wasm_bindgen_futures::spawn_local(async move {
                let _ = cache::ensure(Kind::Series).await;
                if let Some(acc) = store::account() {
                    episodes.set(Some(api::episodes(&acc, &id).await));
                }
            });
        });
    }

    let name = cache::get(Kind::Series)
        .and_then(|d| {
            d.entries
                .iter()
                .find(|e| e.id == p.id)
                .map(|e| e.name.clone())
        })
        .unwrap_or_else(|| t("Serie", "Series").into());
    let logo = store::settings().show_logos;
    let _ = *tick;

    let body = match &*episodes {
        None => html! { <p class="center">{ t("Lade Folgen …", "Loading episodes …") }</p> },
        Some(Err(e)) => html! { <p class="error">{ e }</p> },
        Some(Ok(eps)) if eps.is_empty() => {
            html! { <p class="center">{ t("Keine Folgen gefunden", "No episodes found") }</p> }
        }
        Some(Ok(eps)) => {
            let mut seasons: Vec<String> = vec![];
            for e in eps {
                if !seasons.contains(&e.cat) {
                    seasons.push(e.cat.clone());
                }
            }
            let current = (*season)
                .clone()
                .or_else(|| (seasons.len() == 1).then(|| seasons[0].clone()));
            match current {
                None => html! {
                    <div class="list">
                    { for seasons.iter().map(|sn| {
                        let in_season: Vec<&Entry> = eps.iter().filter(|e| &e.cat == sn).collect();
                        let started = in_season.iter().filter(|e| store::position(&e.id) > 0.0).count();
                        let card = Entry {
                            id: sn.clone(),
                            name: format!("{} {sn}", t("Staffel", "Season")),
                            cat: sn.clone(),
                            icon: in_season.iter().find_map(|e| e.icon.clone()),
                            ext: None,
                            sub: Some(format!("{} {}{}", in_season.len(), t("Folgen", "episodes"), if started > 0 { t(" · ▶ angefangen", " · ▶ started") } else { "" })),
                            archive: false,
                        };
                        let season = season.clone();
                        html!{ <EntryCard entry={card} {logo} onclick={Callback::from(move |e: Entry| season.set(Some(e.id)))} /> }
                    }) }
                    </div>
                },
                Some(sn) => {
                    let all = eps.clone();
                    html! {
                        <div class="list">
                        { for eps.iter().filter(|e| e.cat == sn).cloned().map(|mut e| {
                            let pos = store::position(&e.id);
                            if pos > 0.0 { e.sub = Some(format!("{} · ▶ {} {}", e.sub.clone().unwrap_or_default(), t("weiter bei", "resume at"), fmt_secs(pos))); }
                            let (nav, all) = (nav.clone(), all.clone());
                            let open = Callback::from(move |e: Entry| {
                                let idx = all.iter().position(|x| x.id == e.id).unwrap_or(0);
                                cache::set_queue(Kind::Series, all.clone(), idx);
                                nav.push(&Route::Play);
                            });
                            let (ui, tick) = (ui.clone(), tick.clone());
                            let menu = Callback::from(move |e: Entry| {
                                let mut items: Vec<(String, Callback<()>)> = vec![];
                                let e2 = e.clone(); let ui2 = ui.clone();
                                items.push((t("🔗 Stream-Link kopieren", "🔗 Copy stream link").into(), Callback::from(move |_| {
                                    if let Some(acc) = store::account() { copy_text(&api::stream_url(&acc, Kind::Series, &e2, "")); ui2.toast(t("Link kopiert", "Link copied")); }
                                })));
                                if store::position(&e.id) > 0.0 {
                                    let (id, tick) = (e.id.clone(), tick.clone());
                                    items.push((t("Fortsetzen-Position löschen", "Clear resume position").into(), Callback::from(move |_| { store::set_position(&id, 0.0); tick.set(*tick + 1); })));
                                }
                                ui.menu(&e.name, items);
                            });
                            html!{ <EntryCard entry={e} {logo} onclick={open} onmenu={Some(menu)} /> }
                        }) }
                        </div>
                    }
                }
            }
        }
    };

    let back = {
        let (season, nav) = (season.clone(), nav.clone());
        let multi = matches!(&*episodes, Some(Ok(e)) if e.iter().map(|x| &x.cat).collect::<std::collections::HashSet<_>>().len() > 1);
        Callback::from(move |_| {
            if season.is_some() && multi {
                season.set(None)
            } else {
                nav.back()
            }
        })
    };
    let title = match &*season {
        Some(sn) => format!("{name} – {} {sn}", t("Staffel", "Season")),
        None => name,
    };
    html! {
        <div class="page">
            <header class="topbar">
                <button class="icon" onclick={back}>{"←"}</button>
                <div class="titles"><div class="title">{ title }</div></div>
            </header>
            { body }
        </div>
    }
}
