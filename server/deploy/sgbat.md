# sgbat deployment

Verified deployment: 2026-08-15

- Tailnet URL: `https://sgbat.tail22619.ts.net:8444`
- Tailnet-IP fallback: `http://100.102.128.28:8445`
- Tailscale IPv4: `100.102.128.28`
- App listener: `127.0.0.1:8787`
- systemd unit: `capsule-sms-sync.service`
- Current release: `/opt/capsule-sms-sync/releases/20260815T133243Z`
- Current symlink: `/opt/capsule-sms-sync/current`
- Database: `/var/lib/capsule-sms-sync/capsule-sms.sqlite`
- Environment/credential file: `/etc/capsule-sms-sync.env`
- `ALLOW_INSECURE_HTTP=1` is enabled only for the tailnet-IP socket fallback; the Node listener remains loopback-only.

The application listener is loopback-only. Tailscale Serve owns port 8444 on the Tailscale IPv4 and IPv6 addresses. For Android devices where strict Private DNS blocks MagicDNS, `capsule-sms-sync-tailnet.socket` binds only `100.102.128.28:8445` and forwards to loopback with `systemd-socket-proxyd`. No public Caddy or Docker port is used for this deployment.

## Operations

```bash
ssh sgbat 'systemctl status capsule-sms-sync.service --no-pager'
ssh sgbat 'journalctl -u capsule-sms-sync.service -n 100 --no-pager'
ssh sgbat 'tailscale serve status'
ssh sgbat 'curl --fail --silent http://127.0.0.1:8787/healthz'
ssh sgbat 'systemctl status capsule-sms-sync-tailnet.socket --no-pager'
```

Retrieve the generated administrator username or password only when needed. Do not paste the full environment file into logs or chat:

```bash
ssh sgbat "sudo sed -n 's/^ADMIN_USERNAME=//p' /etc/capsule-sms-sync.env"
ssh sgbat "sudo sed -n 's/^ADMIN_PASSWORD=//p' /etc/capsule-sms-sync.env"
```

Disable only this Tailscale entry without affecting the older 4174/8443 services:

```bash
ssh sgbat 'tailscale serve --https=8444 off'
```

Rollback is performed by repointing `/opt/capsule-sms-sync/current` to a known-good release and restarting `capsule-sms-sync.service`. Do not delete the database during code rollback.
