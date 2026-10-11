package nyanime.addon.api;

import android.content.Context;

/** Optional episode-guides.v1 capability. JSON contains public work metadata only.
 * Calls run off the UI thread. cancel affects only the specified request.
 */
public interface RinEpisodeGuideProvider {
    String search(Context context, String requestId, String identityJson) throws Exception;
    String guide(Context context, String requestId, String reference) throws Exception;
    void cancel(String requestId);
}
