package nyanime.addon.api;

/** A signed Explore add-on. All calls run on a worker thread. */
public interface ExploreFeedV1 extends HostedAddon {
    /**
     * Returns a JSON object with items and hasMore. Each item contains a catalog anime,
     * youtubeId, and an optional metric (label and value). Pages are one-based.
     * Preferences contain a session seed and the user's local recommendation signals.
     * Implementations must keep those signals on-device.
     */
    String loadPage(int page, String preferences, Network network) throws Exception;

    interface Network {
        String postJson(String httpsUrl, String json) throws Exception;
    }
}
