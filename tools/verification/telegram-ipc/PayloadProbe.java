package probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import eu.kanade.tachiyomi.data.telegram.TelegramIpcPayload;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Separate synthetic APK: no account, network, native library, app data or launcher UI. */
public final class PayloadProbe extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i=0;i<count;i++) result.append(value);
        return result.toString();
    }
    private static byte[] hash(String value) throws Exception { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        HandlerThread thread = new HandlerThread("ipc-check");
        thread.start();
        final int count = 26;
        CountDownLatch connected = new CountDownLatch(1), replies = new CountDownLatch(count);
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Map<Integer, byte[]> expected = new ConcurrentHashMap<>();
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger descriptorReplies = new AtomicInteger();
        Messenger incoming = new Messenger(new Handler(thread.getLooper(), message -> {
            try (TelegramIpcPayload payload = TelegramIpcPayload.Companion.receive(message.getData())) {
                require(message.arg2 != android.os.Process.myPid());
                if (message.getData().containsKey("json_descriptor")) descriptorReplies.incrementAndGet();
                String value = payload.read();
                require(Arrays.equals(expected.get(message.arg1), hash(value)));
                require(seen.add(message.arg1));
                replies.countDown();
            } catch (Throwable e) { failure.set(e); }
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { remote.set(new Messenger(binder)); connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { failure.set(new AssertionError("remote service died")); }
        };
        boolean bound = false;
        try {
            bound = getContext().bindService(new Intent(getContext(), EchoService.class), connection, Context.BIND_AUTO_CREATE);
            require(bound && connected.await(10, TimeUnit.SECONDS));
            for (int i=0;i<count;i++) {
                // Large ASCII, multi-byte Unicode, a burst, and small replies after large ones.
                String value = i==0 ? "small" : i==1 ? repeat("x",538304) : i==2 ? repeat("x",2*1024*1024)
                    : i==3 ? repeat("\ud83d\udc31\u6f22\u00e9",100000) : i==count-1 ? "still connected" : repeat("fixture-",10000);
                expected.put(i, hash(value));
                Message request = Message.obtain(null, 1, i, 0); request.replyTo = incoming;
                TelegramIpcPayload.Companion.send(getContext(), remote.get(), request, value);
            }
            require(replies.await(25, TimeUnit.SECONDS));
            if (failure.get()!=null) throw failure.get();
            require(seen.size()==count && descriptorReplies.get()==count-2);
            File directory = new File(getContext().getNoBackupFilesDir(), "telegram-ipc");
            require(Objects.requireNonNull(directory.list()).length==0);
            // Reject an oversized descriptor before allocating a buffer and close it.
            File invalid = File.createTempFile("invalid", ".bin", getContext().getNoBackupFilesDir());
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(invalid, ParcelFileDescriptor.MODE_READ_ONLY);
            invalid.delete();
            Bundle bad = new Bundle(); bad.putParcelable("json_descriptor", descriptor); bad.putInt("json_bytes", 8*1024*1024+1);
            boolean rejected=false;
            try { TelegramIpcPayload.Companion.receive(bad); } catch (IllegalArgumentException e) { rejected=true; }
            require(rejected && !descriptor.getFileDescriptor().valid());
            report.putString("ipc_payload", "passed");
            report.putInt("cross_process_round_trips", seen.size());
            report.putInt("descriptor_round_trips", descriptorReplies.get());
            report.putBoolean("private_files_removed", true);
            report.putBoolean("oversized_descriptor_rejected_and_closed", true);
            finish(Activity.RESULT_OK, report);
        } catch (Throwable e) {
            report.putString("ipc_payload", "failed");
            report.putString("failure_type", e.getClass().getSimpleName());
            report.putInt("round_trips", seen.size());
            finish(Activity.RESULT_CANCELED, report);
        } finally {
            if (bound) getContext().unbindService(connection);
            thread.quitSafely();
        }
    }
}
