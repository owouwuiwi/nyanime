package nyanime.addon.api;
import android.app.Notification;
public interface RinAddonTask {
    RinAddonHost host();
    void update(Notification progress);
    void finish(Notification result);
}
