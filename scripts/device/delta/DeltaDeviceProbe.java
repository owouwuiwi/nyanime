package nyanime.qa;

import android.app.Instrumentation;
import android.content.pm.PackageInfo;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import com.android.apksig.ApkVerifier;
import eu.kanade.tachiyomi.data.updater.delta.DeltaPatch;
import eu.kanade.tachiyomi.data.updater.delta.DeltaRecord;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CancellationException;
import dalvik.system.DexClassLoader;
import kotlin.Unit;

/** Self-targeted test-only package. Never installs, opens or modifies the main app. */
public final class DeltaDeviceProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; start(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static String hash(File file) { return DeltaPatch.INSTANCE.hash(file, () -> Unit.INSTANCE); }
    private Method optimizedValidator;
    private Object optimizedReceiver;
    private Context validationContext;
    private File candidateCode;
    private void checkOptimizedValidator(File candidate, Bundle result) throws Exception {
        String name = arguments.getString("validatorClass");
        if (name == null) return;
        candidateCode = new File(getTargetContext().getCodeCacheDir(), "verified-candidate.apk");
        candidateCode.delete();
        try (FileInputStream source = new FileInputStream(candidate);
             FileOutputStream destination = new FileOutputStream(candidateCode)) {
            require(candidateCode.setReadOnly(), "Cannot protect candidate code before loading");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = source.read(buffer)) != -1) destination.write(buffer, 0, count);
            destination.getFD().sync();
        }
        require(hash(candidateCode).equals(hash(candidate)), "Candidate code copy differs");
        // Keep the candidate's R8-optimized dependencies isolated from the probe's libraries.
        DexClassLoader loader = new DexClassLoader(candidateCode.getAbsolutePath(),
            getTargetContext().getCodeCacheDir().getAbsolutePath(), null, getClass().getClassLoader().getParent());
        Class<?> gate = loader.loadClass(name);
        for (Method method : gate.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 4 && parameters[0] == Context.class && parameters[1] == File.class &&
                parameters[2] == Long.class && parameters[3] == String.class) {
                optimizedValidator = method;
                optimizedValidator.setAccessible(true);
                break;
            }
        }
        require(optimizedValidator != null, "Actual optimized APK validator not found");
        if (!Modifier.isStatic(optimizedValidator.getModifiers())) {
            for (Field field : gate.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == gate) {
                    field.setAccessible(true); optimizedReceiver = field.get(null); break;
                }
            }
            require(optimizedReceiver != null, "Optimized validator receiver not found");
        }
        validationContext = new ContextWrapper(getTargetContext()) {
            @Override public String getPackageName() { return "it.owouwuiwi.nyanime"; }
        };
        optimizedValidator.invoke(optimizedReceiver, validationContext, candidate,
            Long.valueOf(arguments.getString("targetCode")), arguments.getString("targetVersion"));
        result.putBoolean("optimizedApkGateAccepted", true);
        File wrongSigner = new File(getTargetContext().getExternalFilesDir(null), "wrong-signer.apk");
        if (wrongSigner.exists()) {
            requireOptimizedRejection(wrongSigner);
            result.putBoolean("optimizedApkGateRejectedWrongSigner", true);
        }
    }
    private void requireOptimizedRejection(File candidate) throws Exception {
        try {
            optimizedValidator.invoke(optimizedReceiver, validationContext, candidate,
                Long.valueOf(arguments.getString("targetCode")), arguments.getString("targetVersion"));
            throw new AssertionError("Optimized APK gate accepted an invalid candidate");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof Exception, "Unexpected optimized gate failure");
        }
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        File root = getTargetContext().getExternalFilesDir(null);
        File base = new File(root, "base.apk");
        File patch = new File(root, "update.nyadelta");
        File output = new File(root, "reconstructed.apk");
        File cancelled = new File(root, "cancelled.apk");
        File rejected = new File(root, "rejected.apk");
        try {
            require(output.delete() || !output.exists(), "Cannot clear previous probe output");
            cancelled.delete(); rejected.delete();
            String targetHash = arguments.getString("targetSha256");
            DeltaRecord record = new DeltaRecord(arguments.getString("baseSha256"), base.length(),
                targetHash, Long.parseLong(arguments.getString("targetSize")),
                arguments.getString("patchSha256"), patch.length(), "fixture.nyadelta", "fixture.apk");
            long began = SystemClock.elapsedRealtime();
            final long[] progress = {0};
            DeltaPatch.INSTANCE.apply(base, patch, output, record, () -> Unit.INSTANCE, (written, size) -> {
                require(written >= progress[0] && written <= size, "Non-monotonic reconstruction");
                progress[0] = written;
                return Unit.INSTANCE;
            });
            result.putLong("reconstructionMillis", SystemClock.elapsedRealtime() - began);
            require(hash(output).equals(targetHash), "Final APK differs from signed target");
            require(progress[0] == record.getTargetSize(), "Incomplete progress");
            ApkVerifier.Result verification = new ApkVerifier.Builder(output)
                .setMinCheckedPlatformVersion(Build.VERSION.SDK_INT).setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify();
            require(verification.isVerified(), "Android signature verification failed");
            PackageInfo info = getTargetContext().getPackageManager().getPackageArchiveInfo(output.getAbsolutePath(), 0);
            require(info != null && "it.owouwuiwi.nyanime".equals(info.packageName), "Wrong archive package");
            require(arguments.getString("targetVersion").equals(info.versionName), "Wrong archive version");
            result.putString("versionName", info.versionName);
            result.putLong("versionCode", info.getLongVersionCode());
            result.putString("sha256", hash(output));
            result.putBoolean("signatureVerified", true);
            checkOptimizedValidator(output, result);
            final boolean[] stop = {false};
            try {
                DeltaPatch.INSTANCE.apply(base, patch, cancelled, record, () -> {
                    if (stop[0]) throw new CancellationException("Probe cancellation");
                    return Unit.INSTANCE;
                }, (written, size) -> { stop[0] = true; return Unit.INSTANCE; });
                throw new AssertionError("Cancellation was ignored");
            } catch (CancellationException expected) { require(!cancelled.exists(), "Cancelled output survived"); }
            result.putBoolean("cancellationCleaned", true);
            try {
                DeltaRecord wrong = new DeltaRecord(new String(new char[64]).replace('\0', '0'), record.getBaseSize(), targetHash, record.getTargetSize(),
                    record.getPatchSha256(), record.getPatchSize(), record.getPatch(), record.getTarget());
                DeltaPatch.INSTANCE.apply(base, patch, rejected, wrong, () -> Unit.INSTANCE, (written, size) -> Unit.INSTANCE);
                throw new AssertionError("Wrong base accepted");
            } catch (IllegalArgumentException expected) { require(!rejected.exists(), "Rejected output survived"); }
            result.putBoolean("wrongBaseRejected", true);
            // Test the independent crypto gate on altered output, then restore the exact original byte.
            try (RandomAccessFile bytes = new RandomAccessFile(output, "rw")) {
                bytes.seek(100); int original = bytes.readUnsignedByte(); bytes.seek(100); bytes.writeByte(original ^ 1);
                boolean accepted;
                try { accepted = new ApkVerifier.Builder(output).build().verify().isVerified(); }
                catch (Exception invalid) { accepted = false; }
                require(!accepted, "Modified signed APK accepted");
                if (optimizedValidator != null) {
                    requireOptimizedRejection(output);
                    result.putBoolean("optimizedApkGateRejectedTampering", true);
                }
                bytes.seek(100); bytes.writeByte(original); bytes.getFD().sync();
            }
            require(hash(output).equals(targetHash), "Original target not restored");
            result.putBoolean("tamperedSignatureRejected", true);
            result.putString("status", "passed");
            result.putString("stream", "Delta reconstruction, byte identity, Android signature/archive parsing, cancellation and rejection passed.\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            result.putString("status", "failed");
            finish(0, result);
        } finally {
            output.delete(); cancelled.delete(); rejected.delete();
            if (candidateCode != null) candidateCode.delete();
        }
    }
}
