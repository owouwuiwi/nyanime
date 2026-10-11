# Room call engine — removed in 0.33.0.1

The experimental Telegram integration in rooms has been removed at the user's request.
Rooms use their previous Nostr UI and playback flow; they do not create Telegram groups,
start calls, or initialize call-related audio/video components.

Historical implementation and verification notes remain available in Git history before
this removal. They are not a description of current app behavior.

See [Guarda insieme](watch-together.md) for the active room behavior and
[Nyanime Cloud](telegram-cloud.md) for the independently retained Telegram account and backups.
