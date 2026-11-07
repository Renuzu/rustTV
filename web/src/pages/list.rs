use super::TopBar;
use crate::api::{Entry, Kind};
use crate::cache;
use crate::store::{self, t};
use crate::ui::{input_value, input_value_i, item_menu, use_ui, EntryCard};
use crate::Route;
use std::collections::{HashMap, HashSet};
use yew::prelude::*;
use yew_router::prelude::*;

const FAV: &str = "__fav";
const HIST: &str = "__hist";
const PAGE: usize = 200;

pub fn sort_labels() -> [&'static str; 4] {
    [
        t("Name A–Z", "Name A–Z"),
        t("Name Z–A", "Name Z–A"),
        t(
            "Nach Kategorie (Bouquet-Reihenfolge)",
            "By category (bouquet order)",
        ),
        t("Reihenfolge des Anbieters", "Provider order"),
    ]
}

#[derive(Properties, PartialEq)]
pub struct ListProps {
    pub kind: Kind,
    pub vault: bool,
}

#[function_component(ListPage)]
pub fn list_page(p: &ListProps) -> Html {
    let kind = p.kind;
    let vault = p.vault;
    let ui = use_ui();
    let nav = use_navigator().unwrap();
    let tick = use_state(|| 0u32);
    let loading = use_state(|| cache::get(kind).is_none());
    let error = use_state(|| None::<String>);
    let cat = use_state(|| None::<String>);
    let query = use_state(String::new);
    let limit = use_state(|| PAGE);
    let refresh = {
        let tick = tick.clone();
        Callback::from(move |_| tick.set(tick.wrapping_add(1)))
    };

    {
        let (loading, error, cat, limit, refresh) = (
            loading.clone(),
            error.clone(),
            cat.clone(),
            limit.clone(),
            refresh.clone(),
        );
        use_effect_with((kind, vault), move |_| {
            cat.set(None);
            limit.set(PAGE);
            if cache::get(kind).is_none() {
                loading.set(true);
                wasm_bindgen_futures::spawn_local(async move {
                    if let Err(e) = cache::ensure(kind).await {
                        error.set(Some(e));
                    }
                    loading.set(false);
                    refresh.emit(());
                });
            } else {
                loading.set(false);
            }
        });
    }

    let s = store::settings();
    let data = cache::get(kind);
    let cat_names: HashMap<String, String> = data
        .as_ref()
        .map(|d| {
            d.cats
                .iter()
                .map(|c| (c.id.clone(), c.name.clone()))
                .collect()
        })
        .unwrap_or_default();
    let favs = store::favorites(kind);
    let hidden = store::hidden_cats(kind);
    let locked_all = cache::locked_cats(kind);
    let locked = if store::unlocked() || vault {
        HashSet::new()
    } else {
        locked_all.clone()
    };
    let (vc, vi) = (store::vault_cats(kind), store::vault_items(kind));
    let q = query.trim().to_lowercase();
    let sel = (*cat).clone();

    // category dropdown
    let vault_item_cats: HashSet<String> = if vault {
        data.as_ref()
            .map(|d| {
                d.entries
                    .iter()
                    .filter(|e| vi.contains(&e.id))
                    .map(|e| e.cat.clone())
                    .collect()
            })
            .unwrap_or_default()
    } else {
        HashSet::new()
    };
    let cats: Vec<_> = cache::ordered_cats(kind)
        .into_iter()
        .filter(|c| {
            if vault {
                vc.contains(&c.id) || vault_item_cats.contains(&c.id)
            } else {
                !hidden.contains(&c.id) && !vc.contains(&c.id)
            }
        })
        .collect();

    // filter + sort
    let mut list: Vec<Entry> = data
        .as_ref()
        .map(|d| {
            d.entries
                .iter()
                .filter(|e| {
                    cache::is_vault(e, &vc, &vi) == vault
                        && (vault
                            || !hidden.contains(&e.cat)
                            || sel.as_deref() == Some(FAV)
                            || sel.as_deref() == Some(HIST))
                        && !locked.contains(&e.cat)
                        && match sel.as_deref() {
                            None | Some(HIST) => true,
                            Some(FAV) => favs.contains(&e.id),
                            Some(c) => e.cat == c,
                        }
                        && (q.is_empty() || e.name.to_lowercase().contains(&q))
                })
                .cloned()
                .collect()
        })
        .unwrap_or_default();

    if sel.as_deref() == Some(HIST) {
        let pos: HashMap<String, usize> = store::history(kind)
            .into_iter()
            .enumerate()
            .map(|(i, id)| (id, i))
            .collect();
        list.retain(|e| pos.contains_key(&e.id));
        list.sort_by_key(|e| pos[&e.id]);
    } else {
        match s.sort {
            0 => list.sort_by_key(|e| e.name.to_lowercase()),
            1 => {
                list.sort_by_key(|e| e.name.to_lowercase());
                list.reverse();
            }
            2 => {
                let order: HashMap<String, usize> = cache::ordered_cats(kind)
                    .into_iter()
                    .enumerate()
                    .map(|(i, c)| (c.id, i))
                    .collect();
                list.sort_by_key(|e| {
                    (
                        order.get(&e.cat).copied().unwrap_or(usize::MAX),
                        e.name.to_lowercase(),
                    )
                });
            }
            _ => {}
        }
        if s.fav_first && sel.as_deref() != Some(FAV) {
            let (f, r): (Vec<Entry>, Vec<Entry>) =
                list.into_iter().partition(|e| favs.contains(&e.id));
            list = f.into_iter().chain(r).collect();
        }
    }
    for e in list.iter_mut() {
        if e.sub.is_none() {
            e.sub = cat_names.get(&e.cat).cloned();
        }
        if kind != Kind::Live && store::position(&e.id) > 0.0 {
            e.sub = Some(t("▶ weiterschauen", "▶ continue").into());
        }
    }
    let total = list.len();
    let grid = kind != Kind::Live && s.poster_grid;

    let on_cat = {
        let (cat, ui, limit, locked_all) =
            (cat.clone(), ui.clone(), limit.clone(), locked_all.clone());
        Callback::from(move |e: Event| {
            let v = input_value(&e);
            let v = if v.is_empty() { None } else { Some(v) };
            limit.set(PAGE);
            if let Some(c) = &v {
                if !vault && !store::unlocked() && locked_all.contains(c) {
                    let (cat, v) = (cat.clone(), v.clone());
                    // reset the select until the PIN is right
                    let cur = (*cat).clone();
                    cat.set(cur);
                    ui.pin(true, Callback::from(move |_| cat.set(v.clone())));
                    return;
                }
            }
            cat.set(v);
        })
    };
    let on_sort = {
        let refresh = refresh.clone();
        Callback::from(move |e: Event| {
            let mut s = store::settings();
            s.sort = input_value(&e).parse().unwrap_or(0);
            store::save_settings(&s);
            refresh.emit(());
        })
    };
    let open = {
        let (nav, list) = (nav.clone(), list.clone());
        Callback::from(move |e: Entry| {
            if kind == Kind::Series {
                store::add_history(Kind::Series, &e.id);
                nav.push(&Route::Series { id: e.id.clone() });
            } else {
                let idx = list.iter().position(|x| x.id == e.id).unwrap_or(0);
                cache::set_queue(kind, list.clone(), idx);
                nav.push(&Route::Play);
            }
        })
    };
    let menu = {
        let (ui, nav, list, refresh) = (ui.clone(), nav.clone(), list.clone(), refresh.clone());
        Callback::from(move |e: Entry| {
            item_menu(&ui, &nav, kind, &e, list.clone(), refresh.clone())
        })
    };
    let fav = {
        let refresh = refresh.clone();
        Callback::from(move |e: Entry| {
            store::toggle_favorite(kind, &e.id);
            refresh.emit(());
        })
    };

    let tab = |k: Kind| -> Route {
        if vault {
            Route::Vault {
                kind: k.key().into(),
            }
        } else {
            Route::List {
                kind: k.key().into(),
            }
        }
    };
    let title = if vault {
        format!("🔐 {} – {}", t("Tresor", "Vault"), kind.title())
    } else {
        kind.title().to_string()
    };
    let shown: Vec<Entry> = list.into_iter().take(*limit).collect();

    html! {
        <div class="page">
            <TopBar title={title} back={Some(Route::Home)}>
                <Link<Route> to={Route::Bouquets { kind: kind.key().into() }} classes="icon"><span title="Bouquets">{"📚"}</span></Link<Route>>
            </TopBar>
            <nav class="tabs">
                { for Kind::ALL.iter().map(|&k| html!{
                    <Link<Route> to={tab(k)} classes={classes!("tab", (k == kind).then_some("active"))}>{ k.title() }</Link<Route>>
                }) }
            </nav>
            <div class="filters">
                <select onchange={on_cat}>
                    <option value="" selected={sel.is_none()}>{ if vault { t("Alles im Tresor", "Everything in vault") } else { t("Alle Kategorien", "All categories") } }</option>
                    <option value={FAV} selected={sel.as_deref() == Some(FAV)}>{ t("★ Favoriten", "★ Favorites") }</option>
                    <option value={HIST} selected={sel.as_deref() == Some(HIST)}>{ t("🕘 Zuletzt gesehen", "🕘 Recently watched") }</option>
                    { for cats.iter().map(|c| {
                        let lock = !vault && !store::unlocked() && locked_all.contains(&c.id);
                        html!{ <option value={c.id.clone()} selected={sel.as_deref() == Some(c.id.as_str())}>{ if lock { format!("🔒 {}", c.name) } else { c.name.clone() } }</option> }
                    }) }
                </select>
                <select onchange={on_sort} title={t("Sortieren", "Sort")}>
                    { for sort_labels().iter().enumerate().map(|(i, l)| html!{ <option value={i.to_string()} selected={s.sort as usize == i}>{ format!("⇅ {l}") }</option> }) }
                </select>
                <input type="search" placeholder={ t("Suchen …", "Search …") } value={(*query).clone()}
                    oninput={ let (q, l) = (query.clone(), limit.clone()); move |e: InputEvent| { l.set(PAGE); q.set(input_value_i(&e)); } } />
            </div>
            <p class="muted small">{ format!("{total} {}", t("Einträge", "items")) }
                if !locked.is_empty() { { t(" · 🔒 gesperrte Kategorien ausgeblendet", " · 🔒 locked categories hidden") } } </p>
            if *loading { <p class="center">{ t("Lade Liste …", "Loading list …") }</p> }
            if let Some(e) = &*error { <p class="error">{ e }</p> }
            <div class={if grid { "grid" } else { "list" }}>
                { for shown.into_iter().map(|e| {
                    let f = favs.contains(&e.id);
                    html!{ <EntryCard entry={e} {grid} logo={s.show_logos || grid} fav={Some(f)}
                        onclick={open.clone()} onmenu={Some(menu.clone())} onfav={Some(fav.clone())} /> }
                }) }
            </div>
            if total > *limit {
                <button class="more-btn" onclick={ let l = limit.clone(); move |_| l.set(*l + PAGE) }>
                    { format!("{} ({})", t("Mehr anzeigen", "Show more"), total - *limit) }
                </button>
            }
        </div>
    }
}
