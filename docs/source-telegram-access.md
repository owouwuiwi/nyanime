# Optional Telegram access for sources

Status: development; not included in an ordinary OTA release yet.

## Installation and activation

Installing a package and granting execution consent do not grant Telegram access.
A novel source can declare `TelegramBackedSource` and the public broadcast channels
it needs through the optional source API. Existing sources need no new method.
The application contains no channel catalogue or provider-specific parsing rules.

A package containing a Telegram consumer stays disabled until the user:

1. Chooses **Activate** in the Store or the source settings.
2. Completes the existing Telegram login, if needed.
3. Reviews the declared channels and explicitly approves access.
4. Completes any additional document-component consent the source requires.

The approval dialog can join the reviewed public channels. Existing subscriptions
keep their notification settings; muting is an explicit option for new memberships.
Opening a review alone does not join channels. Multiple Telegram consumers in one
package all require approval before activation. Cancelling, an interrupted login,
an unavailable dependency, or an error leaves the package disabled.

The repository enforces this rule independently of the interface. Package identity,
publisher, archive hash, trust, current authentication revision and grants are
checked again before enabling. A cancelled or superseded interface operation cannot
later enable a changed package. Locally persisted enabled records without the
required approvals are migrated to disabled during inventory refresh.

## Account and permission lifetime

Permissions belong to an account, its authenticated-session revision, the package
publisher and source ID, and the exact set of requested channels. A new login epoch
cannot revive an old grant, even when the numeric account ID is unchanged.

Authentication evidence is separate from the engine's connection state. Merely
losing the network, releasing an IPC connection or closing an idle engine does not
mean logout. Listing installed sources reads local evidence only and does not start
TDLib, log in, join channels, or execute a graph.

Logout and definitive authentication challenges invalidate the account evidence.
Revoking a source grant or invalidating authentication disables the package and
removes it from the usable source inventory. Already-running broker reads are
cancelled or rejected before publishing a result. Stored books, reading positions,
notes and downloaded files are not deleted by this change.

Re-enabling remains an explicit user action after login or revocation. Cancelling a
source setup does not log out the shared Telegram account or change Cloud settings.
Leaving channels is a separate explicit choice, offered only for app-managed
memberships which no other source uses. Shared or previously existing subscriptions
are never left automatically.

Channel grants and membership bookkeeping live in private no-backup storage, outside
graph exports and Cloud records. Graphs contain declared capabilities, not Telegram
session credentials. Script consent remains a separate approval.

## ABI and verification

Public novel, Telegram and document API types have consumer keep rules because their
implementations can arrive from separately compiled APKs. The class loader delegates
these contracts to the host, avoiding duplicate interface identities.

The focused tests cover missing login, interrupted multi-channel activation, account
and authentication-revision changes, exact channel/publisher matching, revocation,
late responses, and cancellation. Device testing of the complete Store/login path
and the optimized final APK are separate release gates; passing JVM tests alone is
not a claim that those gates have passed.
