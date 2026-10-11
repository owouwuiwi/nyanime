package probe;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import eu.kanade.tachiyomi.data.telegram.TelegramIpcPayload;

public final class EchoService extends Service {
    private HandlerThread thread;
    private Messenger messenger;
    @Override public void onCreate() {
        thread = new HandlerThread("ipc-fixture");
        thread.start();
        messenger = new Messenger(new Handler(thread.getLooper(), message -> {
            try (TelegramIpcPayload payload = TelegramIpcPayload.Companion.receive(message.getData())) {
                String value = payload.read();
                Message reply = Message.obtain(null, 1, message.arg1, android.os.Process.myPid());
                TelegramIpcPayload.Companion.send(this, message.replyTo, reply, value);
            } catch (Exception e) {
                android.util.Log.e("IpcProbe", "Echo failure: " + e.getClass().getSimpleName());
            }
            return true;
        }));
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public void onDestroy() { thread.quitSafely(); }
}
