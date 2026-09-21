import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Kiem thu socket that, khong can thu vien ngoai hay thao tac giao dien. */
public class ChatIntegrationTest {
    public static void main(String[] args) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process server = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "ChatServer", "0").redirectErrorStream(true).start();
        try {
            BufferedReader log = new BufferedReader(new InputStreamReader(server.getInputStream()));
            String ready = log.readLine();
            check(ready != null && ready.startsWith("Chat server"), "Server starts");
            int port = Integer.parseInt(ready.substring(ready.lastIndexOf(' ') + 1));
            try (Peer alice = new Peer(port, "Alice"); Peer bob = new Peer(port, "Bob");
                 Peer carol = new Peer(port, "Carol")) {
                alice.awaitUsers("Alice", "Bob", "Carol");
                pass("Online list contains all three clients");

                alice.chat("*", "Xin chào cả lớp 👋");
                for (Peer peer : new Peer[]{alice, bob, carol}) {
                    Packet packet = peer.next("CHAT");
                    check("Alice".equals(packet.from) && "*".equals(packet.to) &&
                            "Xin chào cả lớp 👋".equals(packet.text), "Group chat contents");
                }
                pass("Unicode group chat reaches all clients");

                alice.chat("Bob", "Tin riêng");
                check("Tin riêng".equals(bob.next("CHAT").text), "Private recipient");
                check("Tin riêng".equals(alice.next("CHAT").text), "Sender echo");
                carol.assertNoData();
                pass("Private message is not sent to third client");

                // Hai nguoi gui dong thoi vao Bob: goi tin khong duoc xen byte.
                Thread first = new Thread(() -> sendBurst(alice, "A"));
                Thread second = new Thread(() -> sendBurst(carol, "C"));
                first.start();
                second.start();
                java.util.Set<String> messages = new java.util.HashSet<>();
                for (int i = 0; i < 80; i++) messages.add(bob.next("CHAT").text);
                first.join();
                second.join();
                check(messages.size() == 80, "Concurrent message count");
                for (int i = 0; i < 40; i++) {
                    check(messages.contains("A" + i) && messages.contains("C" + i), "Concurrent contents");
                    alice.next("CHAT");
                    carol.next("CHAT");
                }
                pass("Concurrent senders preserve packet boundaries");

                byte[] payload = new byte[256 * 1024 + 13];
                new Random(7).nextBytes(payload);
                alice.file("Bob", "../demo.bin", payload);
                Packet file = bob.next("FILE");
                check("Alice".equals(file.from) && "demo.bin".equals(file.text), "File header");
                check(Arrays.equals(payload, file.bytes), "Binary file bytes");
                alice.next("INFO");
                carol.assertNoData();
                pass("Binary file arrives byte-for-byte, with safe filename and correct recipient");

                alice.file("Bob", "empty.txt", new byte[0]);
                check(bob.next("FILE").bytes.length == 0, "Empty file");
                alice.next("INFO");
                pass("Empty file transfer");

                byte[] maximum = new byte[Protocol.MAX_FILE_SIZE];
                new Random(8).nextBytes(maximum);
                alice.file("Bob", "maximum.bin", maximum);
                check(Arrays.equals(maximum, bob.next("FILE").bytes), "Maximum file");
                alice.next("INFO");
                pass("20 MB file transfer");

                try (Socket duplicate = new Socket("localhost", port)) {
                    duplicate.setSoTimeout(5000);
                    DataOutputStream out = new DataOutputStream(duplicate.getOutputStream());
                    out.writeUTF("LOGIN");
                    out.writeUTF("Alice");
                    out.flush();
                    DataInputStream in = new DataInputStream(duplicate.getInputStream());
                    check("ERROR".equals(in.readUTF()), "Duplicate login rejected");
                    in.readUTF();
                }
                pass("Duplicate username rejected");

                alice.chat("Nobody", "Hello");
                alice.next("ERROR");
                alice.file("Nobody", "missing.bin", payload);
                alice.next("ERROR");
                alice.chat("Bob", "x".repeat(4001));
                alice.next("ERROR");
                alice.chat("Bob", "Vẫn hoạt động");
                check("Vẫn hoạt động".equals(bob.next("CHAT").text), "Valid chat after errors");
                alice.next("CHAT");
                pass("Offline recipients and oversized messages handled without breaking stream");

                bob.close();
                alice.awaitUsers("Alice", "Carol");
                try (Peer reconnected = new Peer(port, "Bob")) {
                    alice.awaitUsers("Alice", "Bob", "Carol");
                    reconnected.chat("Alice", "Đã kết nối lại");
                    check("Đã kết nối lại".equals(alice.next("CHAT").text), "Reconnect message");
                }
                pass("Disconnect updates online list and username can reconnect");

                try (Peer bad = new Peer(port, "Bad")) {
                    bad.output.writeUTF("FILE");
                    bad.output.writeUTF("Alice");
                    bad.output.writeUTF("too-large.bin");
                    bad.output.writeInt(Protocol.MAX_FILE_SIZE + 1);
                    bad.output.flush();
                    try {
                        while (true) bad.readPacket();
                    } catch (EOFException expected) { }
                }
                alice.chat("Carol", "Server còn chạy");
                check("Server còn chạy".equals(carol.next("CHAT").text), "Server survives bad client");
                pass("Invalid file size disconnects only offending client");
            }
            System.out.println("ALL TESTS PASSED");
        } finally {
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly();
        }
    }

    private static void sendBurst(Peer peer, String prefix) {
        try {
            for (int i = 0; i < 40; i++) peer.chat("Bob", prefix + i);
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void pass(String message) { System.out.println("PASS: " + message); }

    private static class Packet {
        String type;
        String from;
        String to;
        String text;
        byte[] bytes;
        List<String> names;
    }

    private static class Peer implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;

        Peer(int port, String name) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(10000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF("LOGIN");
            output.writeUTF(name);
            output.flush();
            check("WELCOME".equals(input.readUTF()), "Login " + name);
        }

        void chat(String to, String text) throws IOException {
            output.writeUTF("CHAT");
            output.writeUTF(to);
            output.writeUTF(text);
            output.flush();
        }

        void file(String to, String filename, byte[] bytes) throws IOException {
            output.writeUTF("FILE");
            output.writeUTF(to);
            output.writeUTF(filename);
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();
        }

        Packet readPacket() throws IOException {
            Packet packet = new Packet();
            packet.type = input.readUTF();
            switch (packet.type) {
                case "USERS":
                    packet.names = new ArrayList<>();
                    int count = input.readInt();
                    for (int i = 0; i < count; i++) packet.names.add(input.readUTF());
                    break;
                case "CHAT":
                    packet.from = input.readUTF();
                    packet.to = input.readUTF();
                    packet.text = input.readUTF();
                    break;
                case "FILE":
                    packet.from = input.readUTF();
                    packet.text = input.readUTF();
                    packet.bytes = Protocol.readFile(input);
                    break;
                case "INFO":
                case "ERROR":
                    packet.text = input.readUTF();
                    break;
                default: throw new IOException("Unexpected packet: " + packet.type);
            }
            return packet;
        }

        Packet next(String type) throws IOException {
            Packet packet;
            do { packet = readPacket(); } while ("USERS".equals(packet.type));
            check(type.equals(packet.type), "Expected " + type + ", got " + packet.type);
            return packet;
        }

        void awaitUsers(String... names) throws IOException {
            List<String> expected = Arrays.asList(names);
            while (true) {
                Packet packet = readPacket();
                check("USERS".equals(packet.type), "Expected online list");
                if (packet.names.equals(expected)) return;
            }
        }

        void assertNoData() throws IOException {
            socket.setSoTimeout(350);
            try {
                while (true) {
                    Packet packet = readPacket();
                    check("USERS".equals(packet.type), "Unexpected private data for third client");
                }
            } catch (SocketTimeoutException expected) {
                // Khong co tin rieng/file gui nham den client nay.
            } finally {
                socket.setSoTimeout(10000);
            }
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
