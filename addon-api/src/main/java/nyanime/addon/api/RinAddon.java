package nyanime.addon.api;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
/** Trusted RIN entry point. No Android package or manifest components are installed. */
public interface RinAddon {
    View createView(Activity activity, Context context, RinAddonHost host);
    void startTask(Context context, String name, Bundle arguments, RinAddonTask task);
    void cancelTask(Context context, String name);
    /** Off-main worker: 0 completed, 1 retry, 2 permanently failed. */
    int runPeriodicTask(Context context, String name, RinAddonHost host);
}
