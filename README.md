<p align="center">
  <img src="web/static/icons/icon-192.png" width="96" alt="rustTV logo" />
</p>

<h1 align="center">rustTV</h1>

<p align="center">
  A fast, self-hosted IPTV player for <b>Xtream Codes</b> providers – written in Rust.<br/>
  Runs in any browser (installable PWA for iPhone, iPad, Android, Mac, PC), as a <b>Windows</b> and <b>Mac app</b>, and as an <b>Android APK</b>.
</p>

<p align="center">
  <img src="docs/screenshots/mobile-home.png" width="200" alt="Home screen" />
  <img src="docs/screenshots/mobile-list.png" width="200" alt="Channel list" />
  <img src="docs/screenshots/mobile-bouquets.png" width="200" alt="Bouquet manager" />
</p>
<p align="center">
  <img src="docs/screenshots/desktop-player.png" width="620" alt="Player with channel list" />
</p>

> **rustTV is only a player.** It does not provide, host or link to any channels, movies or series.
> You need your own subscription from a provider, and you are responsible for using only content you are legally allowed to watch.

---

## Features

- **Live TV, movies and series** from any Xtream Codes provider, multiple accounts
- **Home screen** with big tiles, cover pictures, "recently watched" and "continue watching"
- **Sorting**: A–Z, Z–A, by category (your bouquet order) or provider order, favorites first, search everywhere
- **Series by season** – open a show, pick a season, see only its episodes
- **Multi-view**: up to 4 live channels at once (sound from the selected window)
- **Player**: own control bar (play/pause, stop, seek, volume), switch channels while watching (buttons, channel list, swipe, keyboard), last channel, now/next programme (EPG), aspect ratio, sleep timer, picture-in-picture, fullscreen, playback speed
- **Touch gestures** (can be switched off one by one): brightness (left), volume (right), seek / switch channel (sideways)
- **Resume** movies and episodes, **auto-play** next episode
- **TV guide & catch-up** (archive) when your provider supports it
- **Bouquet manager**: reorder categories, move a whole country to the top (e.g. all `|TR|` categories), show/hide categories or entire countries
- **Parental control**: PIN, locked categories, adult categories locked automatically
- **Vault**: a PIN-protected area – content you move there disappears from all normal lists and search
- **VPN / proxy for rustTV only**: route just this app through your VPN provider's SOCKS5 or HTTP proxy (no fallback to the normal connection)
- **Private by design**: logins, favorites and settings stay on your device (export/import to move them)
- **Update notice** when a new version is released
- German and English interface

## Which version do I need?

| You want to …                                   | Use                                                       |
|-------------------------------------------------|-----------------------------------------------------------|
| Watch on iPhone/iPad, Mac, any browser          | **Server** (Docker or Proxmox) + open it in the browser   |
| Use it on a Windows PC or Mac without any server | **Windows / Mac app** (from Releases)                    |
| Use it on Android                               | Browser (server) **or** the Android APK from Releases     |

The server is needed for browsers because browsers block most IPTV streams (mixed content / CORS).
rustTV's server forwards the provider's API, playlists, streams and pictures so they play everywhere.

---

## Installation

### Option 1 – Docker (any Linux server, NAS, Raspberry Pi)

```bash
mkdir rustv && cd rustv
curl -fsSLO https://raw.githubusercontent.com/Renuzu/rustTV/main/docker-compose.yml
# edit the password in docker-compose.yml, then:
docker compose up -d
```

Open `http://<server-ip>:8080`. Update later with `docker compose pull && docker compose up -d`.

### Option 2 – Proxmox (LXC container, no Docker needed)

1. In the Proxmox web interface click **Create CT**:
   - Template: **Debian 12** (or Ubuntu 22.04/24.04)
   - Disk: 2 GB · CPU: 1 core · Memory: 256 MB · Network: DHCP
2. Start the container and open its **Console**, log in as `root`.
3. Run the installer:
   ```bash
   apt update && apt install -y curl
   bash -c "$(curl -fsSL https://raw.githubusercontent.com/Renuzu/rustTV/main/install.sh)"
   ```
4. Choose a password when asked. The script prints the address, e.g. `http://192.168.1.50:8080`.

To **update**, run the same installer command again. Settings live in `/etc/rustv.env`.

The installer also works on any other Debian/Ubuntu machine with systemd (x86_64 or ARM64).

### Option 3 – Windows app

Download `rustTV-x.y.z-windows-setup.exe` from the [latest release](https://github.com/Renuzu/rustTV/releases/latest) and run it.
The app contains everything (no server needed). When a new version is out, the app shows a banner with a download link.

> Windows may show a SmartScreen warning because the installer is not code-signed: click *More info → Run anyway*.

### Option 4 – Mac app

Download `rustTV-x.y.z-macos.dmg` from the [latest release](https://github.com/Renuzu/rustTV/releases/latest),
open it and drag **rustTV** into *Applications*. The app is not notarized by Apple, so the first start is blocked:
open *System Settings → Privacy & Security* and click **Open Anyway** (only needed once).

### Option 5 – Android APK

Download `rustv-android.apk` from the [latest release](https://github.com/Renuzu/rustTV/releases/latest) and install it
(allow "install unknown apps" for your browser). The app tells you when an update is available and installs it with one tap.

---

## Install as an app on iPhone / iPad / Android (PWA)

1. Open your rustTV address in **Safari** (iPhone/iPad) or **Chrome** (Android).
2. iPhone/iPad: tap **Share → Add to Home Screen**. Android: menu **⋮ → Install app**.
3. rustTV now opens full-screen like a normal app and updates itself when the server is updated.

**HTTPS:** full PWA features (offline start, install prompt on Android) need HTTPS. Put rustTV behind a reverse proxy
such as Caddy, Nginx Proxy Manager or Traefik, or use a VPN like Tailscale/WireGuard to reach it from outside your home.

**iPhone note:** iOS does not allow websites to change the volume, and plays live TV via HLS. If a channel does not
play, try *Settings → Live TV format → HLS* or copy the stream link (⋮ → Copy stream link) into VLC.

---

## Configuration

| Variable            | Default         | Description                                                      |
|---------------------|-----------------|------------------------------------------------------------------|
| `RUSTV_BIND`        | `0.0.0.0:8080`  | Address and port to listen on                                    |
| `RUSTV_PASSWORD`    | *(empty)*       | Password for the web interface – **set this** if others can reach the server |
| `RUSTV_UPSTREAM_PROXY` | *(empty)* | Send all provider traffic through a proxy, e.g. `socks5h://user:pass@proxy.example:1080` or `http://user:pass@host:3128` |
| `RUSTV_USER_AGENT`  | `rustTV/<ver>`   | User-Agent sent to the provider (some require e.g. `VLC/3.0.20`) |

## VPN / proxy

Many VPN providers also offer a **SOCKS5** (or HTTP) proxy login. rustTV can send only its own traffic through it –
the rest of your device keeps using the normal connection. If the proxy does not work, rustTV does **not** fall back
to the direct connection.

- **Windows / Mac app** and **Android**: *Settings → VPN / proxy*, enter server, port, username and password, then *Save & test*.
  The test shows the IP address your IPTV provider sees.
- **Server (Docker / Proxmox)**: set `RUSTV_UPSTREAM_PROXY` (see the table above). It applies to all users of that server.

## Security notes

- Without `RUSTV_PASSWORD`, anyone who can reach the port can use your server as a proxy. Set a password and do not expose it to the internet without HTTPS.
- Your provider login is stored in the browser of each device and sent to *your* rustTV server only when needed. rustTV does not log stream URLs and has no telemetry.
- The only external request made by the app itself is the update check against the GitHub releases API.

## Development

Requirements: Rust (stable), `wasm32-unknown-unknown` target, [Trunk](https://trunkrs.dev).

```bash
rustup target add wasm32-unknown-unknown
cargo install trunk --locked
cd web && trunk build --release && cd ..
cargo run --release -p rustv-server       # http://localhost:8080
```

| Folder     | What                                                                 |
|------------|----------------------------------------------------------------------|
| `server/`  | Axum backend: Xtream API proxy, stream/HLS/image proxy, serves the app |
| `web/`     | Yew (WebAssembly) frontend, PWA                                       |
| `desktop/` | Tauri app for Windows and macOS – embeds the server, opens a native window |
| `android/` | Native Android app (Kotlin, Media3)                                   |

Desktop build: `npm i -g @tauri-apps/cli@^2 && cd desktop && tauri build` (after building the web app).

Releases are built automatically by GitHub Actions when a tag `vX.Y.Z` is pushed: Linux binaries (x86_64/ARM64),
Windows installer, macOS app and a multi-arch Docker image at `ghcr.io/renuzu/rusttv`.

## Credits

Video playback in the browser uses [hls.js](https://github.com/video-dev/hls.js) and
[mpegts.js](https://github.com/xqq/mpegts.js) (both Apache-2.0, see `web/static/vendor/`).

## License

[MIT](LICENSE)
