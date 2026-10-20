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

## Mode A: root (works)

Prerequisite: Magisk/KernelSU, with `su`, `ip`, `iptables` available.

1. Settings -> Tesla compatibility -> connection scheme: Root hotspot routing.
2. Request root, enable Tesla compatibility. Confirm "Root hotspot routing enabled".
3. Turn on the offline-checks toggle (label "Answer Tesla connectivity checks (no SIM)").
4. System hotspot on, Tesla joins it.
5. Tesla browser: `http://100.96.0.1/` (or the configured alias). Web listener
   is on 8080/8443, mapped to 80/443 on the alias by iptables DNAT.

What it does: DNS UDP/53 from hotspot clients is DNATed to the local responder
on `100.96.0.1:18053`; check names resolve to `100.96.0.1`; the web listener
answers the probes. Generic probes (Apple/Google/Microsoft) are only answered
for clients that asked for ConnMan in the last 5 minutes, so phones keep using
cellular data. Queries and replies are logged to
`/data/data/moe.feng.wheelplay.debug/files/tesla-offline-checks.log`.

Observed on a US Model 3: stays connected with the above. Car's own apps
(music, maps) still have no internet.

## Mode B: non-root (not supported for no-upstream)

Root is required because:

- `ip_unprivileged_port_start = 1024`: an unprivileged app cannot bind UDP/53,
  so the local DNS responder cannot be reached by hotspot clients.
- `iptables -t nat` and `ip addr add` are denied without root, so neither DNS
  redirection nor the `100.96.x` alias can be installed.
- `VpnService` only affects the phone's own traffic; hotspot client packets
  bypass it, so it cannot answer Tesla's DNS queries.
- This Moto reports `STA + AP concurrency: false`, so it cannot be a hotspot
  client of another network while sharing its own.

Non-root VPN mode (the other Tesla scheme) only provides address reachability
(`132.226.x.x`), not DNS, so it does not solve the "no internet" prompt.

## Untested alternative: iPhone personal hotspot

Topology: iPhone opens personal hotspot; Android and Tesla join it; Android uses
USB for CarPlay. Tesla then has real internet and no check needs answering.

Open questions:

- iOS hotspot client isolation: can Tesla reach the Android web listener?
- Whether this Model 3's browser rejects private addresses such as
  `192.168.x.x` / `172.20.10.x`. If it does, the `100.96.x` alias is still
  needed, which requires root.

## Open verification

- Whether this Model 3's browser accepts private addresses. Test by opening
  `http://<gateway>/` from the car while connected to the Android hotspot.
