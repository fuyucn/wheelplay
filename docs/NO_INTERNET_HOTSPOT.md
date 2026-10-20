# No-upstream hotspot (no SIM)

When the Android hotspot has no upstream (no SIM, no Wi-Fi STA+AP concurrency on
this Moto), a Tesla on the hotspot reports "no internet" and may disconnect.
This document records what was tried and what is known.

## What works (root required)

Root mode + `offline_checks` toggle (default off). Hotspot DNS (UDP 53) is
redirected to a local responder that resolves known check names to the shared
alias `100.96.0.1`, and the web listener answers:

- `connman.vn.tesla.services`, `connman.vn.cloud.tesla.cn` -> `200` with
  `X-ConnMan-Status: online`, `X-Cache: Hit from cloudfront`
- `www.tesla.com`, `www.microsoft.com`, `www.google.com`, `www.apple.com`,
  `captive.apple.com`, `www.msftconnecttest.com`, `clients3.google.com`,
  `www.gstatic.com`, `connectivitycheck.gstatic.com`, `www.baidu.com`,
  `www.baidu.cn`, `www.qq.com`, `www.tesla.cn` -> `200` / `204`
- gateway `HEAD /` -> `200` with the same ConnMan headers
- `generate_204`, `gen_204` -> `204`

Generic probes are only answered for clients that asked for ConnMan within the
last 5 minutes, so iPhones and other clients still see the hotspot as offline
and use cellular data. Queries and replies are logged to
`/data/data/moe.feng.wheelplay.debug/files/tesla-offline-checks.log`.

Observed on a US Model 3: the car queries `connman.vn.tesla.services`,
`www.tesla.com`, and sends `HEAD /` to the gateway. With the above it stays
connected. Requires root, `su`, `ip`, `iptables`.

## What does not work

- Non-root: port 53 cannot be bound (`ip_unprivileged_port_start=1024`),
  `iptables`/`ip addr` are denied, and `VpnService` does not affect hotspot
  client packets. Non-root VPN mode only provides address reachability, not DNS.
- `STA + AP` concurrency is false on this Moto, so the phone cannot be a
  hotspot client of another network while sharing its own.
- iPhone personal hotspot as upstream: untested. Two open questions remain:
  iOS hotspot client isolation (Tesla reaching the Android web listener) and
  whether this car's browser rejects private addresses (it would then need the
  `100.96.x` alias, which requires root).

## Open verification

- Whether this Model 3's browser accepts private addresses such as
  `192.168.x.x` / `172.20.10.x`. Test by opening `http://<gateway>/` from
  the car while connected to the Android hotspot.
