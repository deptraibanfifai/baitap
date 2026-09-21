import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Giao dien Swing; doc/ghi socket o thread nen de khong treo giao dien. */
public class ChatClient extends JFrame {
    private static final String GROUP = "Tất cả mọi người";
    private final JTextField hostField = new JTextField("172.20.10.3", 12);
    private final JTextField portField = new JTextField("5000", 5);
    private final JTextField nameField = new JTextField(10);
    private final JButton connectButton = new JButton("Kết nối");
    private final JButton disconnectButton = new JButton("Ngắt kết nối");
    private final JTextArea history = new JTextArea();
    private final JComboBox<String> recipientBox = new JComboBox<>();
    private final JTextField messageField = new JTextField();
    private final JButton sendButton = new JButton("Gửi tin");
    private final JButton fileButton = new JButton("Gửi file…");
    private final JLabel statusLabel = new JLabel("Chưa kết nối");
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "socket-sender");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Connection connection;
    private boolean connecting;
    private boolean closing;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatClient().setVisible(true));
    }

    public ChatClient() {
        super("Java Chat — TCP Client / Server");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(850, 570);
        setMinimumSize(new Dimension(760, 450));
        setLocationByPlatform(true);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JPanel login = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        login.add(new JLabel("Server:"));
        login.add(hostField);
        login.add(new JLabel("Cổng:"));
        login.add(portField);
        login.add(new JLabel("Tên:"));
        login.add(nameField);
        login.add(connectButton);
        login.add(disconnectButton);
        root.add(login, BorderLayout.NORTH);

        history.setEditable(false);
        history.setLineWrap(true);
        history.setWrapStyleWord(true);
        history.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        history.setMargin(new Insets(10, 10, 10, 10));
        root.add(new JScrollPane(history), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 8));
        JPanel recipientRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        recipientRow.add(new JLabel("Gửi đến:"));
        recipientBox.setPreferredSize(new Dimension(200, 28));
        recipientRow.add(recipientBox);
        recipientRow.add(fileButton);
        recipientRow.add(new JLabel("File riêng ≤ 20 MB • lưu tại received-files"));
        bottom.add(recipientRow, BorderLayout.NORTH);
        JPanel compose = new JPanel(new BorderLayout(6, 0));
        compose.add(messageField, BorderLayout.CENTER);
        compose.add(sendButton, BorderLayout.EAST);
        bottom.add(compose, BorderLayout.CENTER);
        bottom.add(statusLabel, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);

        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> disconnect(connection, "Đã ngắt kết nối."));
        sendButton.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());
        fileButton.addActionListener(e -> sendFile());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                closing = true;
                if (connection != null) connection.close();
                sender.shutdownNow();
                dispose();
            }
        });
        updateControls();
        append("Hệ thống", "Nhập tên rồi kết nối. Mở thêm cửa sổ client để bắt đầu chat.");
    }

    private void updateControls() {
        boolean online = connection != null;
        connectButton.setEnabled(!online && !connecting);
        disconnectButton.setEnabled(online);
        hostField.setEnabled(!online && !connecting);
        portField.setEnabled(!online && !connecting);
        nameField.setEnabled(!online && !connecting);
        sendButton.setEnabled(online);
        fileButton.setEnabled(online);
        messageField.setEnabled(online);
        recipientBox.setEnabled(online);
        if (!online) {
            recipientBox.removeAllItems();
            recipientBox.addItem(GROUP);
        }
    }

    private void connect() {
        String host = hostField.getText().trim();
        String name = nameField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showError("Cổng phải là số từ 1 đến 65535.");
            return;
        }
        if (host.isEmpty() || !name.matches("[\\p{L}\\p{N}_-]{1,20}")) {
            showError("Nhập server và tên gồm 1–20 chữ cái, chữ số, dấu _ hoặc -.");
            return;
        }
        connecting = true;
        statusLabel.setText("Đang kết nối…");
        updateControls();
        sender.execute(() -> {
// khởi tạo set dữ liệu
                Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setSoTimeout(15000);
                Connection next = new Connection(socket, name);
                next.output.writeUTF("LOGIN");
                next.output.writeUTF(name);
                next.output.flush();
                String reply = next.input.readUTF();
                if ("ERROR".equals(reply)) throw new IOException(next.input.readUTF());
                if (!"WELCOME".equals(reply)) throw new IOException("Server trả lời không hợp lệ.");
                socket.setSoTimeout(0);
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    if (closing) { next.close(); return; }
                    connection = next;
                    statusLabel.setText(name + " • " + host + ":" + port);
                    setTitle("Java Chat — " + name);
                    updateControls();
                    append("Hệ thống", "Đã kết nối với tên " + name + ".");
                    Thread receiver = new Thread(() -> receive(next), "socket-receiver");
                    receiver.setDaemon(true);
                    receiver.start();
                    messageField.requestFocusInWindow();
                });
            } catch (IOException e) {
                try { socket.close(); } catch (IOException ignored) { }
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    statusLabel.setText("Chưa kết nối");
                    updateControls();
                    if (!closing) showError("Không kết nối được: " + e.getMessage());
                });
            }
        });
    }

    private void receive(Connection current) {
        try {
            while (true) {
 // dọc dữ liệu từ server
                             String type = current.input.readUTF();
                switch (type) {
                    case "USERS":
                        int count = current.input.readInt();
                        if (count < 0 || count > 10000) throw new IOException("Danh sách không hợp lệ.");
                        List<String> names = new ArrayList<>();
                        for (int i = 0; i < count; i++) names.add(current.input.readUTF());
                        SwingUtilities.invokeLater(() -> {
                            if (connection != current) return;
                            Object selected = recipientBox.getSelectedItem();
                            recipientBox.removeAllItems();
                            recipientBox.addItem(GROUP);
                            for (String name : names) {
                                if (!name.equals(current.name)) recipientBox.addItem(name);
                            }
                            if (GROUP.equals(selected) ||
                                    (names.contains(selected) && !current.name.equals(selected))) {
                                recipientBox.setSelectedItem(selected);
                            }
                            statusLabel.setText(current.name + " • Online: " + count);
                        });
                        break;
                    case "CHAT":
                        String from = current.input.readUTF();
                        String to = current.input.readUTF();
                        String text = current.input.readUTF();
                        log(current, Protocol.EVERYONE.equals(to) ? from + " → Chung" : from + " → " + to, text);
                        break;
                    case "FILE":
                        String owner = current.input.readUTF();
                        String filename = Protocol.safeFileName(current.input.readUTF());
                        byte[] bytes = Protocol.readFile(current.input);
                        try {
                            Path directory = Paths.get("received-files", current.name).toAbsolutePath();
                            Files.createDirectories(directory);
                            Path saved = Files.createTempFile(directory, "received-", "-" + filename);
                            Files.write(saved, bytes);
                            log(current, "File từ " + owner, bytes.length + " byte; đã lưu: " + saved);
                        } catch (IOException e) {
                            log(current, "Lỗi lưu file", e.getMessage());
                        }
                        break;
                    case "INFO":
                    case "ERROR":
                        log(current, type.equals("ERROR") ? "Lỗi" : "Hệ thống", current.input.readUTF());
                        break;
                    default:
                        throw new IOException("Gói tin không hợp lệ: " + type);
                }
            }
        } catch (IOException e) {
            current.close();
            SwingUtilities.invokeLater(() -> disconnect(current, "Mất kết nối với server."));
        }
    }

    private String recipient() {
        Object selected = recipientBox.getSelectedItem();
        return selected == null || GROUP.equals(selected) ? Protocol.EVERYONE : selected.toString();
    }

    private void sendMessage() {
        Connection current = connection;
        String text = messageField.getText().trim();
        String to = recipient();
        if (current == null || text.isEmpty()) return;
        if (text.length() > Protocol.MAX_MESSAGE_LENGTH) {
            showError("Tin nhắn tối đa 4000 ký tự.");
            return;
        }
        messageField.setText("");
        sender.execute(() -> {
            try {
// gui va nhan DL
                current.output.writeUTF("CHAT");
                current.output.writeUTF(to);
                current.output.writeUTF(text);
                current.output.flush();
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> disconnect(current, "Không gửi được tin: kết nối đã đóng."));
            }
        });
    }

    private void sendFile() {
        Connection current = connection;
        String to = recipient();
        if (current == null) return;
        if (Protocol.EVERYONE.equals(to)) {
            showError("Chọn một người nhận trong ô Gửi đến trước khi gửi file.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        sender.execute(() -> {
            byte[] bytes;
            try {
                if (Files.size(path) > Protocol.MAX_FILE_SIZE) throw new IOException("File vượt quá 20 MB.");
                // Doc co gioi han, ke ca khi file thay doi kich thuoc sau buoc kiem tra.
                try (java.io.InputStream file = Files.newInputStream(path)) {
                    bytes = file.readNBytes(Protocol.MAX_FILE_SIZE + 1);
                }
                if (bytes.length > Protocol.MAX_FILE_SIZE) throw new IOException("File vượt quá 20 MB.");
            } catch (IOException e) {
                log(current, "Lỗi đọc file", e.getMessage());
                return;
            }
            log(current, "Hệ thống", "Đang gửi " + path.getFileName() + " đến " + to + "…");
            try {
                current.output.writeUTF("FILE");
                current.output.writeUTF(to);
                current.output.writeUTF(Protocol.safeFileName(path.getFileName().toString()));
                current.output.writeInt(bytes.length);
                current.output.write(bytes);
                current.output.flush();
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> disconnect(current, "Gửi file bị gián đoạn: kết nối đã đóng."));
            }
        });
    }

    private void disconnect(Connection current, String reason) {
        if (current == null || current != connection) return;
        current.close();
        connection = null;
        statusLabel.setText("Chưa kết nối");
        updateControls();
        append("Hệ thống", reason);
    }

    private void log(Connection current, String label, String text) {
        SwingUtilities.invokeLater(() -> {
            if (connection == current) append(label, text);
        });
    }

    private void append(String label, String text) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        history.append("[" + time + "] " + label + ": " + text + "\n");
        history.setCaretPosition(history.getDocument().getLength());
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Thông báo", JOptionPane.WARNING_MESSAGE);
    }

    private static class Connection {
        final Socket socket;
        final String name;
        final DataInputStream input;
        final DataOutputStream output;
// khoi tao
        Connection(Socket socket, String name) throws IOException {
            this.socket = socket;
            this.name = name;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
