import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Runs outside the app, using the installed APK's exact classes and native library.
 * No permissions, preferences, library data, network requests or app changes.
 * adb shell CLASSPATH=/data/local/tmp/aniyomi-jni-probe.dex app_process /system/bin
 *   ZstdApkProbe /data/app/.../base.apk arm64-v8a
 */
public final class ZstdApkProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected APK path and ABI");
        ClassLoader loader = new PathClassLoader(
                args[0], args[0] + "!/lib/" + args[1], ClassLoader.getSystemClassLoader());
        // Fail as an ordinary Java exception before JNI can abort on a missing class.
        for (String name : new String[]{"ZstdCompressor", "ZstdDecompressor"}) {
            Class<?> type = Class.forName("com.squareup.zstd." + name, false, loader);
            for (String field : new String[]{"inputBytesProcessed", "outputBytesProcessed"}) {
                if (type.getField(field).getType() != int.class) throw new AssertionError(field);
            }
        }
        Class<?> compressorType = Class.forName("com.squareup.zstd.JniZstdCompressor", true, loader);
        Class<?> decompressorType = Class.forName("com.squareup.zstd.JniZstdDecompressor", true, loader);
        Object compressor = compressorType.getConstructor().newInstance();
        Object decompressor = decompressorType.getConstructor().newInstance();
        try {
            byte[] original = "Aniyomi optimized APK JNI regression check".getBytes(StandardCharsets.UTF_8);
            byte[] encoded = new byte[1024];
            Method compress = compressorType.getMethod("compressStream2",
                    byte[].class, int.class, int.class, byte[].class, int.class, int.class, int.class);
            long remaining = (Long) compress.invoke(compressor,
                    encoded, encoded.length, 0, original, original.length, 0, 2);
            int encodedSize = compressorType.getField("outputBytesProcessed").getInt(compressor);
            if (remaining != 0 || encodedSize <= 0) throw new AssertionError("Compression failed");
            byte[] decoded = new byte[1024];
            Method decompress = decompressorType.getMethod("decompressStream",
                    byte[].class, int.class, int.class, byte[].class, int.class, int.class);
            remaining = (Long) decompress.invoke(decompressor,
                    decoded, decoded.length, 0, encoded, encodedSize, 0);
            int decodedSize = decompressorType.getField("outputBytesProcessed").getInt(decompressor);
            if (remaining != 0 || !Arrays.equals(original, Arrays.copyOf(decoded, decodedSize))) {
                throw new AssertionError("Decompression round-trip failed");
            }
            System.out.println("APK_ZSTD_NATIVE_ROUNDTRIP_OK");
        } finally {
            try {
                compressorType.getMethod("close").invoke(compressor);
            } finally {
                decompressorType.getMethod("close").invoke(decompressor);
            }
        }
    }
}
