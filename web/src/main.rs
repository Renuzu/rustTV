mod api;
mod cache;
mod pages;
mod store;
mod ui;

use gloo_net::http::Request;
use pages::*;
use serde::Deserialize;
use store::t;
use ui::Ui;
use yew::prelude::*;
use yew_router::prelude::*;

pub const REPO: &str = "Renuzu/rustTV";

#[derive(Routable, Clone, PartialEq, Debug)]
pub enum Route {
    #[at("/")]
    Home,
    #[at("/list/:kind")]
    List { kind: String },
    #[at("/vault/:kind")]
    Vault { kind: String },
    #[at("/series/:id")]
    Series { id: String },
    #[at("/play")]
    Play,
    #[at("/bouquets/:kind")]
    Bouquets { kind: String },
    #[at("/settings")]
    Settings,
    #[at("/multi")]
    Multi,
    #[at("/accounts")]
    Accounts,
    #[not_found]
    #[at("/404")]
    NotFound,
}

fn switch(r: Route) -> Html {
    match r {
        Route::Home | Route::NotFound => html! { <HomePage /> },
        Route::List { kind } => {
            html! { <ListPage kind={api::Kind::from_key(&kind)} vault={false} /> }
        }
        Route::Vault { kind } => {
            html! { <ListPage kind={api::Kind::from_key(&kind)} vault={true} /> }
        }
        Route::Series { id } => html! { <SeriesPage {id} /> },
        Route::Play => html! { <PlayerPage /> },
        Route::Bouquets { kind } => html! { <BouquetsPage kind={api::Kind::from_key(&kind)} /> },
        Route::Settings => html! { <SettingsPage /> },
        Route::Multi => html! { <MultiPage /> },
        Route::Accounts => html! { <AccountsPage /> },
    }
}

#[derive(Deserialize, Clone, PartialEq, Debug, Default)]
pub struct ServerInfo {
    pub version: String,
    pub mode: String,
    pub auth_required: bool,
    pub authed: bool,
}

#[function_component(App)]
fn app() -> Html {
    let ui = Ui {
        dialog: use_state(|| None),
        toast: use_state(|| None),
    };
    let info = use_state(|| None::<ServerInfo>);
    let has_account = use_state(|| store::account().is_some());

    {
        let info = info.clone();
        use_effect_with((), move |_| {
            wasm_bindgen_futures::spawn_local(async move {
                let i = match Request::get("/api/info").send().await {
                    Ok(r) => r.json::<ServerInfo>().await.unwrap_or_default(),
                    Err(_) => ServerInfo::default(),
                };
                info.set(Some(i));
            });
        });
    }
    {
        let toast = ui.toast.clone();
        use_effect_with((*ui.toast).clone(), move |v| {
            let handle = v
                .as_ref()
                .map(|_| gloo_timers::callback::Timeout::new(2500, move || toast.set(None)));
            move || drop(handle)
        });
    }

    let content = match &*info {
        None => html! { <div class="center">{ t("Lade …", "Loading …") }</div> },
        Some(i) if i.auth_required && !i.authed => {
            let info = info.clone();
            html! { <ServerLogin on_ok={Callback::from(move |_| info.set(Some(ServerInfo { authed: true, ..(*info).clone().unwrap_or_default() })))} /> }
        }
        Some(_) if !*has_account => {
            let h = has_account.clone();
            html! { <div class="page narrow"><h1 class="brand">{"rustTV"}</h1><AccountForm first={true} on_done={Callback::from(move |_| h.set(true))} /></div> }
        }
        Some(_) => html! { <Switch<Route> render={switch} /> },
    };

    html! {
        <ContextProvider<Ui> context={ui.clone()}>
            <ContextProvider<ServerInfo> context={(*info).clone().unwrap_or_default()}>
                <BrowserRouter>
                    { content }
                    if let Some(d) = (*ui.dialog).clone() { { d } }
                </BrowserRouter>
                if let Some(msg) = (*ui.toast).clone() { <div class="toast">{ msg }</div> }
            </ContextProvider<ServerInfo>>
        </ContextProvider<Ui>>
    }
}

fn main() {
    store::init_lang();
    yew::Renderer::<App>::new().render();
}
