# Ứng dụng chat Java — bài demo Lập trình mạng

Ứng dụng dùng mô hình **Client–Server**, có chat chung, chat riêng và truyền file. Viết bằng Java thuần với TCP Socket và Swing; không cần Maven, cơ sở dữ liệu hay thư viện ngoài.

## 1. Chạy nhanh trên Windows

Yêu cầu **JDK 11 trở lên** (cần JDK để biên dịch, chỉ JRE là chưa đủ).

1. Mở `server.bat`. Giữ cửa sổ server chạy, mặc định cổng **5000**.
2. Mở `client.bat`, nhập tên **Alice**, giữ server `localhost`, bấm **Kết nối**.
3. Mở `client.bat` lần nữa, nhập tên **Bob**, bấm **Kết nối**.
4. Chọn **Tất cả mọi người** để chat chung, hoặc chọn **Bob/Alice** để chat riêng. Nhấn Enter hoặc **Gửi tin**.
5. Chọn một người cụ thể, bấm **Gửi file…**, chọn file. File nhận tự lưu vào `received-files/<tên người nhận>/`; khung chat hiển thị đường dẫn đầy đủ.

Script tự tìm JDK từ `JAVA_HOME`, `PATH` hoặc các thư mục cài đặt phổ biến. Nếu không tìm thấy, đặt `JAVA_HOME` trỏ tới thư mục JDK. Script chỉ áp dụng execution policy cho tiến trình PowerShell đang chạy, không đổi cấu hình hệ thống.

Có thể dùng terminal tại thư mục dự án:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 -Mode server
powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 -Mode client
powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 -Mode test
```

Cổng khác: chạy `run.ps1 -Mode server -Port 6000`, rồi nhập cổng `6000` trong client.

Nếu `java` và `javac` đã có trong PATH, có thể biên dịch và chạy thủ công trên Windows/Linux/macOS:

```text
mkdir out
javac -encoding UTF-8 --release 11 -d out src/Protocol.java src/ChatServer.java src/ChatClient.java
java -cp out ChatServer
java -cp out ChatClient
```

Chạy hai lệnh `java` trong hai terminal khác nhau; chạy thêm client ở terminal thứ ba. Trong IDE, mở dự án và chạy `main()` của `ChatServer`, sau đó chạy nhiều instance `ChatClient`. Đặt working directory là thư mục dự án để dễ tìm file nhận.

## 2. Demo trên hai máy cùng mạng LAN

1. Hai máy cùng kết nối một mạng; máy A chạy server và có thể chạy thêm client.
2. Trên máy A chạy `ipconfig`, tìm IPv4 của card mạng đang dùng, ví dụ `192.168.1.10`.
3. Máy B chạy client, nhập địa chỉ IPv4 của máy A vào ô Server và cổng `5000`.
4. Nếu Windows Firewall hỏi, cho phép Java trên mạng riêng (Private). Nếu kết nối vẫn bị chặn, kiểm tra firewall cho cổng TCP đang dùng và tính năng cách ly thiết bị của Wi-Fi.

`localhost` chỉ dùng khi client và server cùng máy. Nếu báo cổng đang được sử dụng, tắt server cũ hoặc đổi cổng cho cả server và client.

## 3. Cấu trúc mã nguồn

| File | Vai trò |
|---|---|
| `src/Protocol.java` | Cổng mặc định, giới hạn file/tin nhắn, đọc đủ dữ liệu file, làm sạch tên file |
| `src/ChatServer.java` | Nhận kết nối, quản lý tên online, chuyển tiếp tin nhắn và file |
| `src/ChatClient.java` | Giao diện Swing, kết nối server, gửi/nhận tin nhắn và lưu file |
| `tests/ChatIntegrationTest.java` | Kiểm thử tự động qua socket thật |
| `run.ps1` | Tìm JDK, biên dịch và chạy chương trình |

## 4. Nguyên lý hoạt động để trình bày với thầy

```text
Client Alice <-- TCP --> Server <-- TCP --> Client Bob
                           ^
                           |
                          TCP
                           |
                       Client Carol

Tin nhắn riêng/file: Alice -> Server -> Bob
Tin nhắn chung:     Alice -> Server -> tất cả client (gồm Alice)
```

- **ServerSocket** lắng nghe kết nối; `accept()` trả về một **Socket** cho mỗi client.
- Server tạo **một thread cho mỗi client**, nên có thể phục vụ nhiều người cùng lúc.
- `ConcurrentHashMap` lưu ánh xạ `tên -> kết nối`. Tên online không được trùng nhau, phân biệt hoa/thường.
- Client đăng nhập bằng tên. Server gửi lại danh sách online khi có người vào/ra. Chọn người nhận ở ô **Gửi đến**.
- `DataInputStream` và `DataOutputStream` đọc/ghi các trường dữ liệu theo thứ tự thống nhất. Chuỗi dùng `writeUTF/readUTF`, độ dài file dùng `writeInt/readInt`, nội dung dùng byte thô.
- **TCP là luồng byte**, không tự phân chia tin nhắn của ứng dụng. Hai phía phải đọc đúng thứ tự và độ dài. `readFully()` đọc đủ số byte của file, không giả định một lần `read()` sẽ nhận toàn bộ file.
- Server dùng `synchronized` khi gửi một gói tin tới một client để các thread không ghi xen byte. Client dùng một hàng đợi gửi tuần tự.
- Luồng nhận riêng và luồng gửi nền giúp giao diện không bị treo vì thao tác mạng. Cập nhật Swing qua `SwingUtilities.invokeLater()`.
- File đi **qua server**, không có kết nối trực tiếp giữa các client. Bản này chọn nhánh Client–Server của đề bài; không triển khai mạng P2P hay mô phỏng đầy đủ Skype/Napster/Gnutella/Kazaa.

### Giao thức tự định nghĩa

Mỗi dòng dưới đây là một gói tin. Mọi trường trừ `số lượng`, `số byte` và `nội dung file` đều là chuỗi `writeUTF`. Hai số là `int` 4 byte.

| Chiều | Thứ tự trường |
|---|---|
| Client → Server | `LOGIN`, tên |
| Server → Client | `WELCOME` hoặc `ERROR`, mô tả lỗi |
| Server → Client | `USERS`, số lượng, tên 1, tên 2, … |
| Client → Server | `CHAT`, người nhận, nội dung |
| Server → Client | `CHAT`, người gửi, người nhận, nội dung |
| Client → Server | `FILE`, người nhận, tên file, số byte, nội dung file |
| Server → Client | `FILE`, người gửi, tên file, số byte, nội dung file |
| Server → Client | `INFO`, thông báo hoặc `ERROR`, mô tả lỗi |

`WELCOME` không có trường mô tả. Người nhận `*` nghĩa là chat chung; file chỉ gửi cho một người cụ thể. Server lấy tên người gửi từ kết nối đã đăng ký.

Ví dụ Alice gửi “Xin chào” cho Bob: client Alice ghi `CHAT`, `Bob`, `Xin chào`. Server đọc gói, tìm Bob rồi ghi `CHAT`, `Alice`, `Bob`, `Xin chào` sang kết nối của Bob và gửi bản sao lại cho Alice. Carol không nhận được tin này.

## 5. Kịch bản demo khoảng 3–5 phút

1. Chạy server và ba client tên Alice, Bob, Carol; cho thấy danh sách online cập nhật.
2. Alice gửi lời chào vào nhóm; cả ba cửa sổ thấy tin.
3. Alice chọn Bob, gửi tin riêng; chỉ Alice và Bob thấy tin.
4. Alice gửi một file `.txt`, ảnh hoặc PDF cho Bob. Mở thư mục theo đường dẫn trong chat, kiểm tra nội dung file nhận.
5. Thử đăng nhập thêm bằng tên Alice: server báo tên đã được sử dụng.
6. Bob ngắt kết nối: Bob biến mất khỏi danh sách. Kết nối lại để minh họa tên được giải phóng.
7. Chỉ vào `accept()`, `new Thread`, `readUTF/writeUTF`, `readFully()` và `synchronized` để giải thích phần lập trình mạng.

## 6. Kiểm thử và giới hạn

Mở `test.bat` hoặc chạy `run.ps1 -Mode test`. Bài kiểm thử tự khởi động server trên cổng trống, tạo nhiều client bằng socket, kiểm tra danh sách online, Unicode, chat riêng, gửi đồng thời, file nhị phân, file rỗng, file 20 MB, tên trùng, người nhận offline, tin quá dài, ngắt/kết nối lại và độ dài file sai. Server kiểm thử tự dừng khi kết thúc. Đây là kiểm thử phần mạng, không thay cho việc thao tác thử giao diện và demo LAN.

Phạm vi cố ý giữ đơn giản cho bài học:

- Không có tài khoản/mật khẩu, mã hóa TLS, cơ sở dữ liệu, lưu lịch sử sau khi đóng hoặc gửi cho người offline.
- File tối đa **20 MB**, đọc vào RAM rồi chuyển tiếp. Một kết nối dùng chung cho chat và file nên file lớn có thể làm tin nhắn chờ; người nhận chậm có thể làm luồng chuyển tiếp chờ. Phù hợp demo ít client.
- Tin nhắn tối đa **4000 ký tự**; tên gồm 1–20 chữ cái, chữ số, `_` hoặc `-`.
- File nhận có tiền tố ngẫu nhiên để tránh ghi đè; chương trình không tự mở file.
- Thông báo “Đã chuyển file” chỉ xác nhận server đã ghi dữ liệu vào kết nối người nhận, chưa phải xác nhận người nhận đã lưu thành công. Phía nhận sẽ hiển thị riêng đường dẫn hoặc lỗi lưu.
- Kết nối bị đóng sẽ được xử lý khi đọc/ghi socket phát hiện lỗi; chưa có heartbeat để phát hiện nhanh trường hợp mất mạng im lặng.

Nếu cần mở rộng sang mô hình kết hợp: server chỉ quản lý danh sách và trao đổi địa chỉ, còn hai client mở socket trực tiếp để truyền file. Đây là hướng phát triển thêm, chưa nằm trong bản demo hiện tại.
