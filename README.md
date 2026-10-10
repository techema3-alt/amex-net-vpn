# AMEX NET VPN

Multi-protocol Android VPN client (SSH family, HTTP CONNECT, VLESS, VMess, Trojan).
Package `com.amexnet.vpn`, universal APK for armeabi-v7a (32-bit) and arm64-v8a (64-bit).

## Build without a PC
1. Create a GitHub repo and push this folder to it (Termux: `git init && git add . && git commit -m init && git remote add origin <url> && git push -u origin main`).
2. Open the repo's Actions tab. The "Build AMEX NET VPN APK" workflow runs on every push, or run it by hand.
3. Download `AMEX-NET-VPN-apk` from the finished run and install `app-release.apk`.

The workflow downloads tun2socks (hev-socks5-tunnel) and the Xray core at build time; they are not stored in the repo.
Change `XRAY_VERSION` in `.github/workflows/build.yml` to use another Xray release.

Optional stable release key: add repo secrets AMEX_KEYSTORE_BASE64, AMEX_KS_PASS, AMEX_KEY_ALIAS, AMEX_KEY_PASS.

## Security notes
- `.amex` files and emoji strings are encrypted with a password chosen at export (PBKDF2-HMAC-SHA256, AES-256-GCM). No key is stored in the app. Share the password separately.
- Protected configs received from someone else hide host, SNI, payload, proxy, username, password and UUID, are redacted from logs and crash reports, and can't be exported again.
- SSH servers are pinned on first connect; a changed host key is refused before the password is sent.
- Saved profiles are encrypted with an Android Keystore key. The local proxy uses a random port and per-connection credentials.
- Optional: App lock (phone PIN/pattern) and screenshot blocking in Settings.
- `server/amex-ssh-user.sh` creates SSH accounts that can only forward ports, with an expiry date, traffic cap and login limit. Set the same date in the profile's Notes & message.
