# Podcasts

A tiny, privacy-first offline podcast catcher for Android. No ads, no accounts, no analytics, no tracking of any kind.

It bundles a curated list of Australian and international feeds (AI/tech and politics focused) and once a week, over WiFi, checks every feed and auto-downloads the newest episodes so everything plays offline with no mobile data at all.

## Features

- 20 bundled shows: ABC News, ABC RN, Guardian Australia, SBS, ASPI, ANU, NYT Hard Fork, DataFramed, HBR IdeaCast and more
- Weekly AlarmManager auto-sync, rescheduled after reboot, downloads only on unmetered WiFi (a menu switch can allow mobile data)
- Keeps the newest 8 episodes per show
- Fully offline playback via a foreground mediaPlayback service with pause/resume/stop controls on the notification
- Playback position saved every 5 seconds and restored on resume
- Add any show by RSS link via long-press; custom feeds are removable
- Manual tap-download on any network

## Install

Grab the latest APK from [Releases](https://github.com/marronejohn/podcasts/releases) and sideload it (allow installs from your browser or files app). Open it once on WiFi and it does its first download straight away.

## Build

The repo contains the full source: manifest, Java sources, resources and a `build.sh` that drives aapt2, javac, d8, zipalign and apksigner. It expects the build toolchain paths as `BT` and `AJ` environment variables and a private `pod.keystore` plus `KS_PASS` for signing (the keystore is deliberately not published).

## Privacy

- No analytics, no ads, no third-party SDKs
- The only network traffic is fetching the podcast RSS feeds and episode audio you choose
- Episodes, the episode index and settings live in the app's own storage on your device

## License

MIT
