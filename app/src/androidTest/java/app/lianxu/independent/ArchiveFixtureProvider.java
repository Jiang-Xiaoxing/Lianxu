package app.lianxu.independent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** Test APK only: fixed synthetic files. Java avoids target-only Kotlin runtime in this process. */
public final class ArchiveFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (uri.getPath() != null && uri.getPath().startsWith("/saved/") && name != null && name.matches("capture-[0-9]+")) {
            File captured = new File(getContext().getCacheDir(), name);
            int flags = "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY : ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY;
            return ParcelFileDescriptor.open(captured, flags);
        }
        if (!"r".equals(mode) || !("linche".equals(name) || "invalid".equals(name))) throw new FileNotFoundException("Unsupported fixture");
        File file = new File(getContext().getCacheDir(), "fixture-" + name);
        try (OutputStream out = new FileOutputStream(file)) {
            if ("invalid".equals(name)) out.write(new byte[]{1, 2, 3});
            else try (InputStream in = getContext().getAssets().open("linche.lianxu.zip")) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/zip"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
