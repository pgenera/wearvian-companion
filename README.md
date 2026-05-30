# wearvian-companion

Companion **Android phone app** for [wearvian](https://github.com/pgenera/wearvian), a Wear OS
app that acts as a Rivian phone key over BLE.

This companion app exists for one reason: the **Rivian phone-key enrollment** step requires a
cloud login (email + password + MFA) that cannot be done offline. The companion app performs
that login, enrolls the watch's public key with Rivian, and hands the resulting identifiers to
the watch over the **Wear OS Data Layer**. After that, the watch operates **fully offline** for
passive unlock/drive; the companion is only needed again for the periodic (~monthly) Rivian
re-authentication.

## Key custody

The watch generates its own EC keypair and **its private key never leaves the watch**. This app
only ever receives the watch's *public* key and submits it to Rivian's `EnrollPhone`. This app
holds no BLE key material.

## Protocol

See [PROTOCOL.md](PROTOCOL.md) for the watch ↔ phone Data Layer message contract.

## Status

Early scaffold. Not affiliated with or endorsed by Rivian. Built against community-documented
APIs for personal use with the owner's own vehicle.

## License

[Apache-2.0](LICENSE).
