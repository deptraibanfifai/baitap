import java.io.DataInputStream;
import java.io.IOException;

/** Cac quy uoc du lieu dung chung giua client va server. */
public final class Protocol {
    public static final int DEFAULT_PORT = 5000;
    public static final int MAX_FILE_SIZE = 20 * 1024 * 1024;
    public static final int MAX_MESSAGE_LENGTH = 4000;
    public static final String EVERYONE = "*";

    private Protocol() { }

    public static byte[] readFile(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_FILE_SIZE) {
            throw new IOException("Kich thuoc file khong hop le (toi da 20 MB).");
        }
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return bytes;
    }

    public static String safeFileName(String name) {
        String leaf = name.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1);
        leaf = leaf.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        if (leaf.length() > 100) leaf = leaf.substring(leaf.length() - 100);
        return leaf.isEmpty() ? "file" : leaf;
    }
}
