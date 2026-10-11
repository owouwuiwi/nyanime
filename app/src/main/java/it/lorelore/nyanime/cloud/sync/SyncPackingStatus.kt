package it.lorelore.nyanime.cloud.sync

/** Last authenticated checkpoint declaration, never a guess about remaining files on Telegram. */
internal data class SyncPackingStatus(
    val supportedDevices: Int = 0,
    val totalDevices: Int = 0,
    val format: Int = 1,
    val documents: Int = 0,
    val chunks: Int = 0,
) {
    val ready get() = totalDevices > 0 && supportedDevices == totalDevices
}
