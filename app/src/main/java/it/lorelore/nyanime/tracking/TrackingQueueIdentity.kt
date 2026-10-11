package it.lorelore.nyanime.tracking

internal fun PendingTrackingEdit.identityKey() =
    "${subject.key}:$trackId:$trackerId:$remoteId:${remoteUrl.length}:$remoteUrl:" +
        "${account.length}:$account"

internal fun PendingTrackingEdit.sameIdentity(other: PendingTrackingEdit) =
    subject == other.subject &&
        entryId == other.entryId &&
        trackId == other.trackId &&
        trackerId == other.trackerId &&
        remoteId == other.remoteId &&
        remoteUrl == other.remoteUrl &&
        account == other.account &&
        accountEpoch == other.accountEpoch
