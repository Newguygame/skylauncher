package org.fathomstudios.skylauncher.android;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

final class LogBook {
    private LogBook() { }
    static File logFile(Context context) {
        return new File(context.getFilesDir(), "skylauncher-android.log");
    }
    static synchronized void write(Context context, String message) {
        try {
            File file = logFile(context);
            if (file.length() > 200_000) {
                File old = new File(context.getFilesDir(), "skylauncher-android.previous.log");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            String cleaned = message == null ? "" : message.replaceAll("[\\r\\n]+", " ");
            String line = LocalDateTime.now() + " " + cleaned + "\n";
            try (FileOutputStream f = new FileOutputStream(file, true)) {
                f.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }
}
