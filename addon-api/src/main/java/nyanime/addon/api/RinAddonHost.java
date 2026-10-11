package nyanime.addon.api;
import android.app.Notification;
import android.app.PendingIntent;
import android.os.Bundle;
import java.util.function.Consumer;
public interface RinAddonHost {
    Bundle appearance();
    String storageNamespace();
    boolean enabled();
    PendingIntent reopen();
    PendingIntent stopTask(String name);
    void close();
    void requestNotifications(Consumer<Boolean> result);
    void startTask(String name, Bundle arguments, Notification initial);
    void scheduleTask(String name, int hours, boolean enabled);
}
