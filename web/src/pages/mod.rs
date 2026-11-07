mod accounts;
mod bouquets;
mod home;
mod list;
mod player;
mod series;
mod settings;

pub use accounts::{AccountForm, AccountsPage, ServerLogin};
pub use bouquets::BouquetsPage;
pub use home::HomePage;
pub use list::ListPage;
pub use player::PlayerPage;
pub use series::SeriesPage;
pub use settings::SettingsPage;

use crate::Route;
use yew::prelude::*;
use yew_router::prelude::*;

#[derive(Properties, PartialEq)]
pub struct TopBarProps {
    pub title: AttrValue,
    #[prop_or_default]
    pub sub: Option<AttrValue>,
    #[prop_or_default]
    pub back: Option<Route>,
    /// Use browser history instead of a fixed route.
    #[prop_or_default]
    pub back_history: bool,
    #[prop_or_default]
    pub children: Children,
}

#[function_component(TopBar)]
pub fn top_bar(p: &TopBarProps) -> Html {
    let nav = use_navigator();
    let back = if p.back_history {
        let nav = nav.clone();
        Some(Callback::from(move |_| {
            if let Some(n) = &nav {
                n.back();
            }
        }))
    } else {
        p.back.clone().map(|r| {
            let nav = nav.clone();
            Callback::from(move |_| {
                if let Some(n) = &nav {
                    n.push(&r);
                }
            })
        })
    };
    html! {
        <header class="topbar">
            if let Some(cb) = back { <button class="icon" onclick={cb} title="←">{"←"}</button> }
            <div class="titles">
                <div class="title">{ &p.title }</div>
                if let Some(s) = &p.sub { <div class="subtitle">{ s }</div> }
            </div>
            <div class="actions-right">{ p.children.clone() }</div>
        </header>
    }
}
