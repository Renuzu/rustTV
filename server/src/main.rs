use rustv_server::{serve, Config, VERSION};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> std::io::Result<()> {
    if std::env::args().any(|a| a == "--version" || a == "-V") {
        println!("{VERSION}");
        return Ok(());
    }
    if std::env::args().any(|a| a == "--help" || a == "-h") {
        println!("ruSTv {VERSION} – self-hosted IPTV player\n\nEnvironment variables:\n  RUSTV_BIND        address to listen on (default 0.0.0.0:8080)\n  RUSTV_PASSWORD    password for the web interface (recommended)\n  RUSTV_USER_AGENT  User-Agent sent to the IPTV provider");
        return Ok(());
    }
    tracing_subscriber::fmt()
        .with_env_filter(std::env::var("RUST_LOG").unwrap_or_else(|_| "info".into()))
        .init();

    let bind = std::env::var("RUSTV_BIND").unwrap_or_else(|_| "0.0.0.0:8080".into());
    let cfg = Config::from_env();
    let listener = TcpListener::bind(&bind).await?;
    tracing::info!("ruSTv {VERSION} listening on http://{bind}");
    if cfg.password.is_none() {
        tracing::warn!(
            "RUSTV_PASSWORD is not set – anyone who can reach this port can use the proxy"
        );
    }

    tokio::select! {
        r = serve(listener, cfg) => r,
        _ = tokio::signal::ctrl_c() => { tracing::info!("shutting down"); Ok(()) }
    }
}
