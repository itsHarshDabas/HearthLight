# Reaching your PC from anywhere

The gateway only listens on `127.0.0.1:8787`. Pick **one** of these to reach it from your phone.

## Option A: Tailscale (recommended)

A private network between your devices. Nothing is exposed to the internet, it's free for
personal use, and you get real HTTPS.

1. Install Tailscale on the PC and on your phone and sign in with the same account: <https://tailscale.com/download>.
2. In the [admin console → DNS](https://login.tailscale.com/admin/dns), make sure **MagicDNS** is on and enable **HTTPS Certificates**.
3. On the PC (admin PowerShell):
   ```powershell
   tailscale serve --bg 8787
   ```
   It prints a URL like `https://my-pc.tail1234.ts.net`. That is your **Gateway URL** in the app.
   Check with `tailscale serve status`; undo with `tailscale serve reset`.
4. On the phone, keep the Tailscale app connected (it can be set to "always on" in Android VPN settings).

`tailscale serve` is tailnet-only. **Don't** use `tailscale funnel` unless you deliberately want a public URL.

No HTTPS certs? You can instead use `http://<pc-tailscale-ip>:8787` (`tailscale ip -4`) after setting
`host = "0.0.0.0"` in `config.toml`. Traffic is still WireGuard-encrypted inside Tailscale, but your
LAN can then reach port 8787 too, so allow it only on the Tailscale interface in Windows Firewall.
Serve is simpler and safer.

## Option B: Cloudflare Tunnel

Gives you a public HTTPS URL on your own domain (domain must be on Cloudflare). Use it if you want
web apps or other people to reach the API without installing Tailscale.

```powershell
winget install --id Cloudflare.cloudflared
cloudflared tunnel login
cloudflared tunnel create local-ai
cloudflared tunnel route dns local-ai ai.example.com
```

`%USERPROFILE%\.cloudflared\config.yml`:

```yaml
tunnel: local-ai
credentials-file: C:\Users\YOU\.cloudflared\<tunnel-id>.json
ingress:
  - hostname: ai.example.com
    service: http://127.0.0.1:8787
  - service: http_status:404
```

Run `cloudflared tunnel run local-ai` (or `cloudflared service install` to run it as a service).
Then set `public_base_url = "https://ai.example.com"` in `config.toml`.

Things to know:
- The URL is public, so the API key is your only protection. Use long keys (the defaults are fine), one per client, and revoke unused ones.
- Cloudflare drops requests that send nothing for about 100 s. Streaming chat is fine. For images, use the job API (`/v1/images/jobs`, which the app does) rather than the blocking `/v1/images/generations`.
- Want an extra layer? Put **Cloudflare Access** in front with a service token. The Android app doesn't send Access headers yet, so this is for server-to-server use.
- Quick throwaway test without a domain: `cloudflared tunnel --url http://127.0.0.1:8787` prints a random `trycloudflare.com` URL.

## Never do this

- Router port forwarding to 8787, 1234 or 8188.
- Starting LM Studio with "Serve on Local Network" or ComfyUI with `--listen` and exposing those ports. They have no authentication.
