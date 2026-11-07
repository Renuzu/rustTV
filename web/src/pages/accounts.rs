use super::TopBar;
use crate::api;
use crate::cache;
use crate::store::{self, t, Account};
use crate::ui::{input_value_i, use_ui};
use crate::Route;
use gloo_net::http::Request;
use yew::prelude::*;

// ------------------------------------------------------------ server password

#[derive(Properties, PartialEq)]
pub struct ServerLoginProps {
    pub on_ok: Callback<()>,
}

#[function_component(ServerLogin)]
pub fn server_login(p: &ServerLoginProps) -> Html {
    let pw = use_state(String::new);
    let err = use_state(|| None::<String>);
    let submit = {
        let (pw, err, ok) = (pw.clone(), err.clone(), p.on_ok.clone());
        Callback::from(move |e: SubmitEvent| {
            e.prevent_default();
            let (pw, err, ok) = (pw.clone(), err.clone(), ok.clone());
            wasm_bindgen_futures::spawn_local(async move {
                let body = serde_json::json!({ "password": *pw }).to_string();
                let r = Request::post("/api/login")
                    .header("Content-Type", "application/json")
                    .body(body);
                match r {
                    Ok(req) => match req.send().await {
                        Ok(resp) if resp.ok() => ok.emit(()),
                        _ => err.set(Some(t("Falsches Passwort", "Wrong password").into())),
                    },
                    Err(e) => err.set(Some(e.to_string())),
                }
            });
        })
    };
    html! {
        <div class="page narrow">
            <h1 class="brand">{"rustTV"}</h1>
            <form class="card-form" onsubmit={submit}>
                <p>{ t("Dieser Server ist mit einem Passwort geschützt.", "This server is password protected.") }</p>
                <input type="password" placeholder={t("Server-Passwort", "Server password")} autofocus=true
                    oninput={ let pw = pw.clone(); move |e: InputEvent| pw.set(input_value_i(&e)) } />
                if let Some(e) = &*err { <p class="error">{ e }</p> }
                <button type="submit">{ t("Anmelden", "Sign in") }</button>
            </form>
        </div>
    }
}

// ------------------------------------------------------------ Xtream account form

#[derive(Properties, PartialEq)]
pub struct AccountFormProps {
    #[prop_or_default]
    pub first: bool,
    pub on_done: Callback<()>,
}

#[function_component(AccountForm)]
pub fn account_form(p: &AccountFormProps) -> Html {
    let name = use_state(String::new);
    let server = use_state(String::new);
    let user = use_state(String::new);
    let pass = use_state(String::new);
    let busy = use_state(|| false);
    let err = use_state(|| None::<String>);

    let submit = {
        let (name, server, user, pass, busy, err, done) = (
            name.clone(),
            server.clone(),
            user.clone(),
            pass.clone(),
            busy.clone(),
            err.clone(),
            p.on_done.clone(),
        );
        Callback::from(move |e: SubmitEvent| {
            e.prevent_default();
            if server.trim().is_empty() || user.trim().is_empty() || pass.is_empty() {
                err.set(Some(
                    t(
                        "Bitte Server, Benutzer und Passwort ausfüllen.",
                        "Please fill in server, username and password.",
                    )
                    .into(),
                ));
                return;
            }
            let acc = Account {
                id: format!("{}", js_sys::Date::now() as u64),
                name: if name.trim().is_empty() {
                    user.trim().to_string()
                } else {
                    name.trim().to_string()
                },
                server: server.trim().to_string(),
                user: user.trim().to_string(),
                pass: (*pass).clone(),
            };
            let (busy, err, done) = (busy.clone(), err.clone(), done.clone());
            busy.set(true);
            err.set(None);
            wasm_bindgen_futures::spawn_local(async move {
                match api::login(&acc).await {
                    Ok(()) => {
                        store::add_account(acc);
                        cache::clear();
                        done.emit(());
                    }
                    Err(e) if e == "auth" => err.set(Some(
                        t(
                            "Server-Passwort fehlt – Seite neu laden",
                            "Server password missing – reload the page",
                        )
                        .into(),
                    )),
                    Err(e) => err.set(Some(e)),
                }
                busy.set(false);
            });
        })
    };
    let field = |st: &UseStateHandle<String>, ph: &'static str, kind: &'static str| {
        let st = st.clone();
        html! { <input type={kind} placeholder={ph} value={(*st).clone()} autocomplete="off"
        oninput={move |e: InputEvent| st.set(input_value_i(&e))} /> }
    };
    html! {
        <form class="card-form" onsubmit={submit}>
            if p.first { <p>{ t("Gib die Xtream-Codes-Zugangsdaten deines Anbieters ein.", "Enter your provider's Xtream Codes login.") }</p> }
            { field(&name, t("Name des Kontos (optional)", "Account name (optional)"), "text") }
            { field(&server, t("Server, z. B. http://server.tv:8080", "Server, e.g. http://server.tv:8080"), "text") }
            { field(&user, t("Benutzername", "Username"), "text") }
            { field(&pass, t("Passwort", "Password"), "password") }
            if let Some(e) = &*err { <p class="error">{ e }</p> }
            <button type="submit" disabled={*busy}>{ if *busy { t("Prüfe …", "Checking …") } else { t("Anmelden", "Sign in") } }</button>
            <p class="muted small">{ t("Die Zugangsdaten bleiben auf diesem Gerät.", "Your login stays on this device.") }</p>
        </form>
    }
}

// ------------------------------------------------------------ account list

#[function_component(AccountsPage)]
pub fn accounts_page() -> Html {
    let ui = use_ui();
    let tick = use_state(|| 0u32);
    let adding = use_state(|| false);
    let accs = store::accounts();
    let active = store::account().map(|a| a.id).unwrap_or_default();
    let _ = *tick;
    let refresh = {
        let tick = tick.clone();
        move || tick.set(tick.wrapping_add(1))
    };

    let info = {
        let ui = ui.clone();
        Callback::from(move |_: MouseEvent| {
            let ui = ui.clone();
            wasm_bindgen_futures::spawn_local(async move {
                if let Some(acc) = store::account() {
                    match api::account_info(&acc).await {
                        Ok(rows) => ui.message(
                            t("Kontoinfo", "Account info"),
                            &rows
                                .iter()
                                .map(|(a, b)| format!("{a}: {b}"))
                                .collect::<Vec<_>>()
                                .join("\n"),
                        ),
                        Err(e) => ui.message(t("Fehler", "Error"), &e),
                    }
                }
            });
        })
    };

    html! {
        <div class="page narrow">
            <TopBar title={t("Konten", "Accounts")} back={Some(Route::Home)} />
            <div class="list">
            { for accs.iter().map(|a| {
                let is_active = a.id == active;
                let switch = { let (id, r) = (a.id.clone(), refresh.clone()); Callback::from(move |_: MouseEvent| { store::set_active(&id); cache::clear(); r(); }) };
                let del = { let (id, name, r, ui) = (a.id.clone(), a.name.clone(), refresh.clone(), ui.clone()); Callback::from(move |_: MouseEvent| {
                    let (id, r) = (id.clone(), r.clone());
                    ui.menu(&format!("„{name}“ {}", t("löschen?", "delete?")), vec![(t("Ja, löschen", "Yes, delete").into(), Callback::from(move |_| {
                        store::remove_account(&id); cache::clear(); r();
                        if store::account().is_none() { let _ = web_sys::window().unwrap().location().reload(); }
                    }))]);
                }) };
                html!{
                    <div class={classes!("card", "row", is_active.then_some("active"))}>
                        <div class="txt" onclick={switch}><div class="name">{ if is_active { "✓ " } else { "" } }{ &a.name }</div><div class="sub">{ &a.user }</div></div>
                        <button class="more" onclick={del}>{"🗑"}</button>
                    </div>
                }
            }) }
            </div>
            <div class="quick">
                <button onclick={info}>{ t("ℹ Kontoinfo", "ℹ Account info") }</button>
                <button onclick={ let a = adding.clone(); move |_| a.set(!*a) }>{ t("+ Konto hinzufügen", "+ Add account") }</button>
            </div>
            if *adding {
                <AccountForm on_done={ let (a, r) = (adding.clone(), refresh.clone()); Callback::from(move |_| { a.set(false); r(); }) } />
            }
        </div>
    }
}
