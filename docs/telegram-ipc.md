# Telegram process transport

The Android application and the TDLib engine communicate through a private bound service. Binder has a shared, limited transaction buffer; UTF-8 JSON size does not predict the UTF-16 parcel size. A large history page must not be embedded in a Bundle string or truncated merely to fit that buffer.

`TelegramIpcPayload` keeps short control messages inline and transfers larger messages through a read-only file descriptor. The backing file lives in application-private storage and is unlinked before the payload is written. Sender and receiver close their descriptor copies deterministically; receiving allocations are bounded to 8 MiB. Descriptor reads run on the ordered IO consumer, outside the main thread.

An IPC send error does not prove that a peer process died. The engine removes subscribers only on `DeadObjectException`; transient delivery errors notify the client to reconnect. No private payload, account, key or message is logged. The client fails pending requests rather than declaring success when delivery is uncertain.

This is a local transport correction. Telegram session keys, archives, synchronization packet formats, membership and durable outboxes are unchanged. An interrupted backup or sync send is reconciled with existing server messages before another send, as before.

See the [real-device Binder regression fixture](../tools/verification/telegram-ipc/README.md). Final application checks must also cover a full backup, sync receipts, account preservation and recovery of an interrupted upload. A successful synthetic transport test alone is not proof of Cloud delivery.
