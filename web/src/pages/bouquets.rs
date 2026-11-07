use super::TopBar;
use crate::api::{Category, Kind};
use crate::cache;
use crate::store::{self, t};
use crate::ui::{use_ui, Modal};
use crate::Route;
use std::collections::HashMap;
use yew::prelude::*;
use yew_router::prelude::*;

#[derive(Properties, PartialEq)]
pub struct BouquetProps {
    pub kind: Kind,
}

fn prefixes(cats: &[Category]) -> Vec<(String, usize)> {
    let mut m: HashMap<String, usize> = HashMap::new();
    for c in cats {
        if let Some(p) = cache::prefix(&c.name) {
            *m.entry(p).or_default() += 1;
        }
    }
    let mut v: Vec<(String, usize)> = m.into_iter().filter(|(_, n)| *n >= 2).collect();
    v.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    v
}

#[function_component(BouquetsPage)]
pub fn bouquets_page(p: &BouquetProps) -> Html {
    let kind = p.kind;
    let ui = use_ui();
    let tick = use_state(|| 0u32);
    let pin_ok = use_mut_ref(|| false);
    let refresh = {
        let tick = tick.clone();
        Callback::from(move |_: ()| tick.set(tick.wrapping_add(1)))
    };
    {
        let refresh = refresh.clone();
        use_effect_with(kind, move |_| {
            wasm_bindgen_futures::spawn_local(async move {
                let _ = cache::ensure(kind).await;
                refresh.emit(());
            });
        });
    }

    let cats = cache::ordered_cats(kind);
    let counts: HashMap<String, usize> = cache::get(kind)
        .map(|d| {
            let mut m = HashMap::new();
            for e in d.entries.iter() {
                *m.entry(e.cat.clone()).or_insert(0) += 1;
            }
            m
        })
        .unwrap_or_default();
    let hidden = store::hidden_cats(kind);
    let locked = cache::locked_cats(kind);
    let manual_locked = store::locked_cats(kind);
    let vault = store::vault_cats(kind);

    let save_order = move |v: &[Category]| {
        store::set_cat_order(kind, &v.iter().map(|c| c.id.clone()).collect::<Vec<_>>())
    };
    let with_pin = {
        let (ui, pin_ok) = (ui.clone(), pin_ok.clone());
        move |f: Callback<()>| {
            if *pin_ok.borrow() {
                f.emit(());
                return;
            }
            let pin_ok = pin_ok.clone();
            ui.pin(
                false,
                Callback::from(move |_| {
                    *pin_ok.borrow_mut() = true;
                    f.emit(());
                }),
            );
        }
    };

    let mv = |from: usize, to: usize| {
        let (cats, refresh) = (cats.clone(), refresh.clone());
        Callback::from(move |_: MouseEvent| {
            let mut v = cats.clone();
            if from < v.len() && to < v.len() {
                let c = v.remove(from);
                v.insert(to, c);
                save_order(&v);
                refresh.emit(());
            }
        })
    };

    // menu actions
    let country_top = {
        let (ui, cats, refresh) = (ui.clone(), cats.clone(), refresh.clone());
        Callback::from(move |_: MouseEvent| {
            let pf = prefixes(&cats);
            if pf.is_empty() {
                ui.toast(t("Keine Länderkürzel erkannt", "No country prefixes found"));
                return;
            }
            let items = pf
                .into_iter()
                .map(|(code, n)| {
                    let (cats, refresh, ui2, c2) =
                        (cats.clone(), refresh.clone(), ui.clone(), code.clone());
                    (
                        format!("{code}  ({n})"),
                        Callback::from(move |_| {
                            let (top, rest): (Vec<Category>, Vec<Category>) =
                                cats.iter().cloned().partition(|c| {
                                    cache::prefix(&c.name).as_deref() == Some(c2.as_str())
                                });
                            let v: Vec<Category> = top.into_iter().chain(rest).collect();
                            save_order(&v);
                            refresh.emit(());
                            ui2.toast(format!("{c2} ↑"));
                        }),
                    )
                })
                .collect();
            ui.menu(
                t("Welches Land nach oben?", "Which country to the top?"),
                items,
            );
        })
    };
    let countries_visible = {
        let (ui, cats, refresh) = (ui.clone(), cats.clone(), refresh.clone());
        Callback::from(move |_: MouseEvent| {
            let pf = prefixes(&cats);
            if pf.is_empty() {
                ui.toast(t("Keine Länderkürzel erkannt", "No country prefixes found"));
                return;
            }
            ui.show(html! { <CountryDialog kind={kind} cats={cats.clone()} prefixes={pf} on_done={refresh.clone()} /> });
        })
    };
    let az = |rev: bool| {
        let (cats, refresh) = (cats.clone(), refresh.clone());
        Callback::from(move |_: MouseEvent| {
            let mut v = cats.clone();
            v.sort_by_key(|c| c.name.to_lowercase());
            if rev {
                v.reverse();
            }
            save_order(&v);
            refresh.emit(());
        })
    };
    let show_all = {
        let r = refresh.clone();
        Callback::from(move |_: MouseEvent| {
            store::set_hidden_cats(kind, &Default::default());
            r.emit(());
        })
    };
    let reset = {
        let r = refresh.clone();
        Callback::from(move |_: MouseEvent| {
            store::set_cat_order(kind, &[]);
            r.emit(());
        })
    };
    let more = {
        let ui = ui.clone();
        let (a, b, c, d, e, f) = (
            country_top.clone(),
            countries_visible.clone(),
            az(false),
            az(true),
            show_all.clone(),
            reset.clone(),
        );
        Callback::from(move |_: MouseEvent| {
            let ev = || web_sys::MouseEvent::new("click").unwrap();
            let (a, b, c, d, e, f) = (
                a.clone(),
                b.clone(),
                c.clone(),
                d.clone(),
                e.clone(),
                f.clone(),
            );
            ui.menu(
                t("Bouquets", "Bouquets"),
                vec![
                    (
                        t(
                            "⬆ Land nach oben holen (z. B. TR)",
                            "⬆ Move a country to the top (e.g. TR)",
                        )
                        .into(),
                        Callback::from(move |_| a.emit(ev())),
                    ),
                    (
                        t("🌍 Länder ein-/ausblenden", "🌍 Show / hide countries").into(),
                        Callback::from(move |_| b.emit(ev())),
                    ),
                    (
                        t("A–Z sortieren", "Sort A–Z").into(),
                        Callback::from(move |_| c.emit(ev())),
                    ),
                    (
                        t("Z–A sortieren", "Sort Z–A").into(),
                        Callback::from(move |_| d.emit(ev())),
                    ),
                    (
                        t("👁 Alle einblenden", "👁 Show all").into(),
                        Callback::from(move |_| e.emit(ev())),
                    ),
                    (
                        t("↺ Reihenfolge zurücksetzen", "↺ Reset order").into(),
                        Callback::from(move |_| f.emit(ev())),
                    ),
                ],
            );
        })
    };

    let last = cats.len().saturating_sub(1);
    html! {
        <div class="page">
            <TopBar title={t("Bouquets verwalten", "Manage bouquets")} back={Some(Route::List { kind: kind.key().into() })}>
                <button class="icon" onclick={more} title="⋮">{"⋮"}</button>
            </TopBar>
            <nav class="tabs">
                { for Kind::ALL.iter().map(|&k| html!{
                    <Link<Route> to={Route::Bouquets { kind: k.key().into() }} classes={classes!("tab", (k == kind).then_some("active"))}>{ k.title() }</Link<Route>>
                }) }
            </nav>
            <div class="quick">
                <button onclick={country_top}>{ t("⬆ Land nach oben", "⬆ Country to top") }</button>
                <button onclick={countries_visible}>{ t("🌍 Länder ein/aus", "🌍 Countries on/off") }</button>
            </div>
            <p class="muted small">{ t("▲▼ = verschieben · ⤒ = ganz nach oben · 👁 ein/aus · 🔒 PIN nötig · 🔐 in den Tresor",
                                       "▲▼ = move · ⤒ = to top · 👁 show/hide · 🔒 needs PIN · 🔐 to vault") }</p>
            if cats.is_empty() { <p class="center">{ t("Lade Kategorien …", "Loading categories …") }</p> }
            <div class="list">
            { for cats.iter().enumerate().map(|(i, c)| {
                let is_hidden = hidden.contains(&c.id);
                let is_locked = locked.contains(&c.id);
                let is_vault = vault.contains(&c.id);
                let auto_locked = is_locked && !manual_locked.contains(&c.id);
                let id = c.id.clone();
                let toggle_hide = { let (id, r) = (id.clone(), refresh.clone()); Callback::from(move |_: MouseEvent| { store::toggle_hidden_cat(kind, &id); r.emit(()); }) };
                let toggle_lock = { let (id, r, wp, ui) = (id.clone(), refresh.clone(), with_pin.clone(), ui.clone()); Callback::from(move |_: MouseEvent| {
                    if auto_locked { ui.toast(t("Automatisch gesperrt (Erwachsenen-Inhalt) – in den Einstellungen änderbar", "Locked automatically (adult) – change in settings")); return; }
                    let (id, r) = (id.clone(), r.clone());
                    wp(Callback::from(move |_| { store::toggle_locked_cat(kind, &id); store::set_unlocked(false); r.emit(()); }));
                }) };
                let toggle_vault = { let (id, r, wp) = (id.clone(), refresh.clone(), with_pin.clone()); Callback::from(move |_: MouseEvent| {
                    let (id, r) = (id.clone(), r.clone());
                    wp(Callback::from(move |_| { store::toggle_vault_cat(kind, &id); r.emit(()); }));
                }) };
                let mut sub = vec![format!("{} {}", counts.get(&c.id).copied().unwrap_or(0), t("Einträge", "items"))];
                if is_hidden { sub.push(t("ausgeblendet", "hidden").into()); }
                if is_locked { sub.push(t("gesperrt", "locked").into()); }
                if is_vault { sub.push(t("im Tresor", "in vault").into()); }
                html!{
                    <div class={classes!("bq", is_hidden.then_some("off"))}>
                        <div class="bq-move">
                            <button disabled={i == 0} onclick={mv(i, 0)} title="⤒">{"⤒"}</button>
                            <button disabled={i == 0} onclick={mv(i, i.saturating_sub(1))}>{"▲"}</button>
                            <button disabled={i >= last} onclick={mv(i, (i + 1).min(last))}>{"▼"}</button>
                        </div>
                        <div class="txt"><div class="name">{ &c.name }</div><div class="sub">{ sub.join(" · ") }</div></div>
                        <button class={classes!("tg", (!is_hidden).then_some("on"))} onclick={toggle_hide}>{"👁"}</button>
                        <button class={classes!("tg", is_locked.then_some("on"))} onclick={toggle_lock}>{"🔒"}</button>
                        <button class={classes!("tg", is_vault.then_some("on"))} onclick={toggle_vault}>{"🔐"}</button>
                    </div>
                }
            }) }
            </div>
        </div>
    }
}

#[derive(Properties, PartialEq)]
struct CountryProps {
    kind: Kind,
    cats: Vec<Category>,
    prefixes: Vec<(String, usize)>,
    on_done: Callback<()>,
}

#[function_component(CountryDialog)]
fn country_dialog(p: &CountryProps) -> Html {
    let ui = use_ui();
    let hidden = store::hidden_cats(p.kind);
    let checked = use_state(|| {
        p.prefixes
            .iter()
            .map(|(code, _)| {
                p.cats.iter().any(|c| {
                    cache::prefix(&c.name).as_deref() == Some(code.as_str())
                        && !hidden.contains(&c.id)
                })
            })
            .collect::<Vec<bool>>()
    });
    let save = {
        let (ui, checked, kind, cats, prefixes, done) = (
            ui.clone(),
            checked.clone(),
            p.kind,
            p.cats.clone(),
            p.prefixes.clone(),
            p.on_done.clone(),
        );
        Callback::from(move |_: MouseEvent| {
            let mut h = store::hidden_cats(kind);
            for (i, (code, _)) in prefixes.iter().enumerate() {
                for c in cats
                    .iter()
                    .filter(|c| cache::prefix(&c.name).as_deref() == Some(code.as_str()))
                {
                    if checked[i] {
                        h.remove(&c.id);
                    } else {
                        h.insert(c.id.clone());
                    }
                }
            }
            store::set_hidden_cats(kind, &h);
            ui.close();
            done.emit(());
        })
    };
    let close = ui.clone();
    html! {
        <Modal title={t("Länder anzeigen (Haken = sichtbar)", "Show countries (checked = visible)")}>
            <div class="menu">
            { for p.prefixes.iter().enumerate().map(|(i, (code, n))| {
                let c = checked.clone();
                html!{ <label class="check"><input type="checkbox" checked={checked[i]}
                    onchange={move |_| { let mut v = (*c).clone(); v[i] = !v[i]; c.set(v); }} />{ format!(" {code}  ({n})") }</label> }
            }) }
            </div>
            <div class="actions">
                <button class="ghost" onclick={move |_| close.close()}>{ t("Abbrechen", "Cancel") }</button>
                <button onclick={save}>{ t("Speichern", "Save") }</button>
            </div>
        </Modal>
    }
}
