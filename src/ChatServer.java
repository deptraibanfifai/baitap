import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ChatServer extends JFrame {
    private final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final JTextField portField = new JTextField("5000", 5);
    private final JButton startButton = new JButton("Khởi động Server");
    private final JButton stopButton = new JButton("Dừng Server");
    private final JTextArea logArea = new JTextArea();

    // Bảng hiển thị thông tin Client
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"Tên Client", "IP Client", "Port Client", "Thời gian vào"}, 0);
    private final JTable clientTable = new JTable(tableModel);

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatServer().setVisible(true));
    }

    public ChatServer() {
        super("Java Chat Server");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 550);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        setContentPane(root);

        // --- Panel Control (Phía Bắc) ---
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        topPanel.add(new JLabel("Cổng (Port):"));
        topPanel.add(portField);
        topPanel.add(startButton);
        topPanel.add(stopButton);
        stopButton.setEnabled(false);
        root.add(topPanel, BorderLayout.NORTH);

        // --- Bảng danh sách Client & Nhật ký Log (Ở giữa) ---
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JScrollPane tableScrollPane = new JScrollPane(clientTable);
        tableScrollPane.setBorder(BorderFactory.createTitledBorder("Danh sách Client đang Online"));

        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(BorderFactory.createTitledBorder("Nhật ký Server (Log)"));

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScrollPane, logScrollPane);
        centerSplit.setDividerLocation(200);
        root.add(centerSplit, BorderLayout.CENTER);

        // --- Event Listeners ---
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
    }

    private void log(String message) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + time + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private synchronized void startServer() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Port phải là một số nguyên!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                isRunning = true;
                SwingUtilities.invokeLater(() -> {
                    startButton.setEnabled(false);
                    stopButton.setEnabled(true);
                    portField.setEnabled(false);
                });
                log("Server đã khởi động thành công trên cổng " + port);

                while (isRunning) {
                    Socket socket = serverSocket.accept();
                    new Thread(new ClientHandler(socket), "client-" + socket.getPort()).start();
                }
            } catch (IOException e) {
                if (isRunning) {
                    log("Lỗi ServerSocket: " + e.getMessage());
                }
            } finally {
                stopServer();
            }
        });
        serverThread.start();
    }

    private synchronized void stopServer() {
        if (!isRunning && serverSocket == null) return;
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            // Ngắt tất cả client đang kết nối
            for (ClientHandler client : clients.values()) {
                client.close();
            }
            clients.clear();
        } catch (IOException e) {
            log("Lỗi khi đóng server: " + e.getMessage());
        }

        SwingUtilities.invokeLater(() -> {
            tableModel.setRowCount(0);
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            portField.setEnabled(true);
        });
        log("Server đã dừng hẳn.");
    }

    private synchronized void broadcastUsers() {
        List<String> names = new ArrayList<>(clients.keySet());
        Collections.sort(names);
        for (ClientHandler client : clients.values()) {
            client.send(out -> {
                out.writeUTF("USERS");
                out.writeInt(names.size());
                for (String name : names) out.writeUTF(name);
            });
        }
    }

    @FunctionalInterface
    private interface PacketWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private DataInputStream input;
        private DataOutputStream output;
        private String name;
        private boolean registered;
        private String connectTime;
        private String clientIp;
        private int clientPort;

        ClientHandler(Socket socket) {
            this.socket = socket;
            this.clientIp = socket.getInetAddress().getHostAddress();
            this.clientPort = socket.getPort();
        }

        synchronized boolean send(PacketWriter packet) {
            try {
                packet.write(output);
                output.flush();
                return true;
            } catch (IOException e) {
                close();
                return false;
            }
        }

        private void error(String message) {
            send(out -> {
                out.writeUTF("ERROR");
                out.writeUTF(message);
            });
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(15000);
                input = new DataInputStream(socket.getInputStream());
                output = new DataOutputStream(socket.getOutputStream());

                if (!"LOGIN".equals(input.readUTF())) return;
                name = input.readUTF().trim();

                if (!name.matches("[\\p{L}\\p{N}_-]{1,20}")) {
                    error("Tên gồm 1–20 chữ cái, chữ số, dấu _ hoặc -.");
                    return;
                }

                synchronized (this) {
                    if (clients.putIfAbsent(name, this) != null) {
                        error("Tên này đang được sử dụng.");
                        return;
                    }
                    registered = true;
                    connectTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    send(out -> out.writeUTF("WELCOME"));
                }

                socket.setSoTimeout(0);

                // Cập nhật GUI Server khi có Client vào
                log("Client '" + name + "' đã vào [IP: " + clientIp + " | Port: " + clientPort + " | Thời gian vào: " + connectTime + "]");
                SwingUtilities.invokeLater(() ->
                        tableModel.addRow(new Object[]{name, clientIp, clientPort, connectTime})
                );

                broadcastUsers();

                while (true) {
                    String type = input.readUTF();
                    if ("CHAT".equals(type)) {
                        handleChat();
                    } else if ("FILE".equals(type)) {
                        handleFile();
                    } else {
                        throw new IOException("Loại gói tin không hợp lệ: " + type);
                    }
                }
            } catch (IOException e) {
                // Client ngắt kết nối hoặc gặp lỗi
            } finally {
                close();
                if (registered) {
                    String leaveTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    log("Client '" + name + "' đã ra [Port: " + clientPort + " | Thời gian ra: " + leaveTime + "]");

                    // Cập nhật lại Bảng GUI Server khi Client ra
                    SwingUtilities.invokeLater(() -> {
                        for (int i = 0; i < tableModel.getRowCount(); i++) {
                            if (tableModel.getValueAt(i, 0).equals(name)) {
                                tableModel.removeRow(i);
                                break;
                            }
                        }
                    });

                    if (clients.remove(name, this)) {
                        broadcastUsers();
                    }
                }
            }
        }

        private void handleChat() throws IOException {
            String recipient = input.readUTF();
            String text = input.readUTF();
            if (text.trim().isEmpty() || text.length() > Protocol.MAX_MESSAGE_LENGTH) {
                error("Tin nhắn phải có từ 1 đến 4000 ký tự.");
                return;
            }
            PacketWriter packet = out -> {
                out.writeUTF("CHAT");
                out.writeUTF(name);
                out.writeUTF(recipient);
                out.writeUTF(text);
            };
            if (Protocol.EVERYONE.equals(recipient)) {
                for (ClientHandler client : clients.values()) client.send(packet);
            } else {
                ClientHandler target = clients.get(recipient);
                if (target == null || !target.send(packet)) {
                    error("Người nhận đã offline.");
                } else if (target != this) {
                    send(packet);
                }
            }
        }

        private void handleFile() throws IOException {
            String recipient = input.readUTF();
            String filename = Protocol.safeFileName(input.readUTF());
            byte[] bytes = Protocol.readFile(input);
            ClientHandler target = clients.get(recipient);
            if (target == null) {
                error("Không gửi được file: người nhận đã offline.");
                return;
            }
            boolean sent = target.send(out -> {
                out.writeUTF("FILE");
                out.writeUTF(name);
                out.writeUTF(filename);
                out.writeInt(bytes.length);
                out.write(bytes);
            });
            if (sent) {
                send(out -> {
                    out.writeUTF("INFO");
                    out.writeUTF("Đã chuyển file " + filename + " đến kết nối của " + recipient + ".");
                });
            } else {
                error("Không chuyển được file: kết nối người nhận đã đóng.");
            }
        }

        private void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}