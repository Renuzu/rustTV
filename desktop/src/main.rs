//! ruSTv for Windows and macOS: runs the ruSTv server inside the app on 127.0.0.1
//! and shows the web app in a native window. No separate server needed.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::TcpListener;

/// Fixed port range so the app keeps its saved data (browser storage is per address).
const PORTS: std::ops::RangeInclusive<u16> = 47811..=47830;

fn main() {
    let listener = PORTS
        .clone()
        .find_map(|p| TcpListener::bind(("127.0.0.1", p)).ok())
        .or_else(|| TcpListener::bind("127.0.0.1:0").ok())
        .expect("no free local port");
    let port = listener.local_addr().expect("addr").port();
    listener.set_nonblocking(true).expect("nonblocking");

    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().expect("runtime");
        rt.block_on(async move {
            let l = tokio::net::TcpListener::from_std(listener).expect("listener");
            let mut cfg = rustv_server::Config::from_env();
            cfg.mode = "desktop".into();
            cfg.password = None; // only reachable from this PC
            let _ = rustv_server::serve(l, cfg).await;
        });
    });

    let url: tauri::Url = format!("http://127.0.0.1:{port}/").parse().expect("url");
    tauri::Builder::default()
        .setup(move |app| {
            tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::External(url.clone()))
                .title("ruSTv")
                // Links to other websites (e.g. the update download on GitHub)
                // open in the normal browser instead of inside the app.
                .on_navigation(|u| {
                    let local = matches!(u.host_str(), Some("127.0.0.1") | Some("localhost"));
                    if !local && (u.scheme() == "http" || u.scheme() == "https") {
                        let _ = open::that_detached(u.as_str());
                        return false;
                    }
                    true
                })
                .inner_size(1280.0, 800.0)
                .min_inner_size(480.0, 360.0)
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running ruSTv");
}
