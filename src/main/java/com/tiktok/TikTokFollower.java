package com.tiktok;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TikTokFollower {
    private static final String TIKTOK_PACKAGE = "com.ss.android.ugc.trill";
    private static final String TIKTOK_ACTIVITY = "com.ss.android.ugc.trill.splash.SplashActivity";

    // Static map để quản lý port cho từng thiết bị
    private static final Map<String, Integer> DEVICE_PORTS = new ConcurrentHashMap<>();
    private static int nextPort = 8200;

    private AndroidDriver driver;
    private List<String> userIds;
    private String deviceId;
    private long delayTime;
    private boolean isConnected = false;

    public TikTokFollower(String userIdsFilePath, String deviceId, long delayTime) {
        this.userIds = readUserIds(userIdsFilePath);
        this.deviceId = deviceId;
        this.delayTime = delayTime;

        // Thêm shutdown hook để xử lý khi người dùng nhấn Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nĐang thoát chương trình...");
            close();
            System.out.println("Đã thoát chương trình.");
        }));
    }

    public static List<String> getAllDevices() {
        List<String> devices = new ArrayList<>();
        try {
            Process process = Runtime.getRuntime().exec("adb devices");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            boolean isFirstLine = true;
            while ((line = reader.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }
                if (line.contains("device") && !line.startsWith("List")) {
                    String id = line.split("\\s+")[0];
                    System.out.println("Đã phát hiện thiết bị: " + id);
                    devices.add(id);
                }
            }
            if (devices.isEmpty()) {
                System.err.println("Không tìm thấy thiết bị Android nào!");
            }
        } catch (IOException e) {
            System.err.println("Lỗi khi lấy danh sách thiết bị: " + e.getMessage());
        }
        return devices;
    }

    private String getDeviceId() {
        return this.deviceId;
    }

    private List<String> readUserIds(String filePath) {
        List<String> ids = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    ids.add(line.trim());
                }
            }
        } catch (IOException e) {
            System.err.println("Lỗi khi đọc file: " + e.getMessage());
        }
        return ids;
    }

    /**
     * Lấy port duy nhất cho mỗi thiết bị để tránh xung đột
     */
    private synchronized int getSystemPortForDevice(String udid) {
        if (!DEVICE_PORTS.containsKey(udid)) {
            DEVICE_PORTS.put(udid, nextPort++);
            System.out.println("Gán port " + (nextPort-1) + " cho thiết bị " + udid);
        }
        return DEVICE_PORTS.get(udid);
    }

    /**
     * Kiểm tra tính hợp lệ và khả năng kết nối của thiết bị
     */
    private boolean validateDevice(String udid) {
        try {
            System.out.println("Đang validate thiết bị: " + udid);

            // Kiểm tra trạng thái thiết bị
            Process process = Runtime.getRuntime().exec("adb -s " + udid + " shell getprop ro.boot.serialno");
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String serialno = reader.readLine();

            if (serialno == null || serialno.trim().isEmpty()) {
                System.err.println("Thiết bị " + udid + " không phản hồi getprop");
                return false;
            }

            // Test ADB connection cơ bản
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell echo 'test'");
            process.waitFor();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String response = reader.readLine();

            if (!"test".equals(response)) {
                System.err.println("ADB connection không ổn định cho thiết bị " + udid);
                return false;
            }

            // Kiểm tra trạng thái boot
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell getprop sys.boot_completed");
            process.waitFor();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String bootCompleted = reader.readLine();

            if (!"1".equals(bootCompleted)) {
                System.err.println("Thiết bị " + udid + " chưa boot hoàn tất");
                return false;
            }

            System.out.println("Thiết bị " + udid + " validation thành công");
            return true;

        } catch (Exception e) {
            System.err.println("Lỗi validate thiết bị " + udid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Restart ADB connection cho thiết bị (đặc biệt hữu ích cho USB)
     */
    private void restartAdbForDevice(String udid) {
        try {
            System.out.println("Restart ADB connection cho thiết bị " + udid);

            // Nếu là WiFi device, disconnect và reconnect
            if (udid.contains(":")) {
                Runtime.getRuntime().exec("adb disconnect " + udid).waitFor();
                Thread.sleep(2000);
                Runtime.getRuntime().exec("adb connect " + udid).waitFor();
                Thread.sleep(3000);
            } else {
                // Với USB device, kill và restart adb server
                Runtime.getRuntime().exec("adb kill-server").waitFor();
                Thread.sleep(2000);
                Runtime.getRuntime().exec("adb start-server").waitFor();
                Thread.sleep(3000);
            }

        } catch (Exception e) {
            System.err.println("Lỗi restart ADB: " + e.getMessage());
        }
    }

    /**
     * Kiểm tra xem UIAutomator2 server có thể khởi động được không
     */
    private boolean canStartUIAutomator2(String udid) {
        try {
            // Kiểm tra xem có process UIAutomator2 nào đang chạy không
            Process process = Runtime.getRuntime().exec("adb -s " + udid + " shell ps | grep uiautomator");
            process.waitFor();

            // Kill existing UIAutomator2 processes
            Runtime.getRuntime().exec("adb -s " + udid + " shell pkill -f uiautomator").waitFor();
            Thread.sleep(1000);

            return true;
        } catch (Exception e) {
            System.err.println("Không thể kiểm tra UIAutomator2 trên thiết bị " + udid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Kiểm tra và dọn dẹp port đang sử dụng
     */
    private void cleanupPort(int port) {
        try {
            // Kiểm tra port có đang được sử dụng không (Windows)
            Process process = Runtime.getRuntime().exec("netstat -ano | findstr :" + port);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();

            if (line != null && line.contains(":" + port)) {
                System.out.println("Port " + port + " đang được sử dụng, đang cố gắng giải phóng...");
                // Có thể thêm logic kill process nếu cần
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    public void connectToDevice() {
        try {
            String udid = getDeviceId();
            if (udid == null) {
                System.err.println("Không tìm thấy thiết bị Android nào! Hãy kiểm tra lại kết nối ADB.");
                return;
            }

            System.out.println("=== BẮT ĐẦU KẾT NỐI THIẾT BỊ: " + udid + " ===");

            // Bước 1: Validate thiết bị trước khi kết nối
            if (!validateDevice(udid)) {
                System.err.println("Thiết bị " + udid + " không hợp lệ, bỏ qua");
                return;
            }

            // Bước 2: Kiểm tra các điều kiện cần thiết
            if (!checkDeviceRequirements(udid)) {
                return;
            }

            // Bước 3: Chuẩn bị kết nối
            int systemPort = getSystemPortForDevice(udid);
            cleanupPort(systemPort);

            // Bước 4: Nếu là thiết bị USB và đã validate thất bại trước đó, thử restart ADB
            boolean isUSBDevice = !udid.contains(":");
            if (isUSBDevice) {
                System.out.println("Phát hiện thiết bị USB, thực hiện các bước chuẩn bị đặc biệt...");
                if (!canStartUIAutomator2(udid)) {
                    restartAdbForDevice(udid);
                    // Validate lại sau khi restart
                    if (!validateDevice(udid)) {
                        System.err.println("Thiết bị USB " + udid + " vẫn không ổn định sau restart");
                        return;
                    }
                }
            }

            // Bước 5: Tạo options với cấu hình tối ưu
            UiAutomator2Options options = createOptimizedOptions(udid, systemPort);

            // Bước 6: Thử kết nối với retry logic cải tiến
            System.out.println("Đang kết nối tới Appium server với port " + systemPort + "...");

            int maxRetries = isUSBDevice ? 6 : 4; // USB device cần nhiều lần thử hơn
            int retryCount = 0;
            Exception lastException = null;

            while (retryCount < maxRetries) {
                try {
                    driver = new AndroidDriver(new URL("http://127.0.0.1:4723"), options);
                    isConnected = true;
                    System.out.println("✅ Đã kết nối thành công với thiết bị " + udid);

                    // Test connection bằng cách lấy package hiện tại
                    String currentPackage = driver.getCurrentPackage();
                    System.out.println("Package hiện tại: " + currentPackage);

                    return;

                } catch (Exception e) {
                    lastException = e;
                    retryCount++;

                    System.out.println(String.format("❌ Lần thử %d/%d thất bại cho thiết bị %s",
                            retryCount, maxRetries, udid));
                    System.out.println("Lỗi: " + e.getMessage());

                    if (retryCount < maxRetries) {
                        // Tăng thời gian delay theo từng lần thử
                        long delay = 3000 + (2000 * retryCount);
                        System.out.println("⏳ Đợi " + delay + "ms trước khi thử lại...");
                        Thread.sleep(delay);

                        // Các bước khắc phục theo lần thử
                        if (retryCount == 2) {
                            System.out.println("🔄 Thực hiện cleanup UIAutomator2...");
                            canStartUIAutomator2(udid);
                        } else if (retryCount == 3 && isUSBDevice) {
                            System.out.println("🔄 Restart ADB cho thiết bị USB...");
                            restartAdbForDevice(udid);
                        } else if (retryCount == 4) {
                            // Thử port khác
                            systemPort = getSystemPortForDevice(udid + "_retry");
                            options.setSystemPort(systemPort);
                            System.out.println("🔄 Thử port mới: " + systemPort);
                        }
                    }
                }
            }

            // Nếu hết lần thử
            System.err.println("❌ KHÔNG THỂ KẾT NỐI với thiết bị " + udid + " sau " + maxRetries + " lần thử");
            printDetailedError(lastException, udid, isUSBDevice);

        } catch (Exception e) {
            System.err.println("=== LỖI NGHIÊM TRỌNG KHI KẾT NỐI THIẾT BỊ ===");
            System.err.println("Chi tiết lỗi: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Kiểm tra các yêu cầu cơ bản của thiết bị
     */
    private boolean checkDeviceRequirements(String udid) {
        try {
            // Kiểm tra USB Debugging
            Process process = Runtime.getRuntime().exec("adb -s " + udid + " shell settings get global adb_enabled");
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String adbEnabled = reader.readLine();
            if (!"1".equals(adbEnabled)) {
                System.err.println("❌ USB Debugging chưa được bật trên thiết bị " + udid);
                return false;
            }

            // Kiểm tra màn hình khóa (simplified check)
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell dumpsys power | grep 'Display Power'");
            process.waitFor();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String powerState = reader.readLine();
            if (powerState != null && powerState.contains("OFF")) {
                System.err.println("❌ Màn hình thiết bị " + udid + " đang tắt");
                return false;
            }

            // Kiểm tra TikTok
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell pm list packages | grep com.ss.android.ugc.trill");
            process.waitFor();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String tiktokPackage = reader.readLine();

            if (tiktokPackage == null || !tiktokPackage.contains("com.ss.android.ugc.trill")) {
                System.err.println("❌ TikTok chưa được cài đặt trên thiết bị " + udid);
                return false;
            }

            System.out.println("✅ TikTok đã được cài đặt trên thiết bị " + udid);
            return true;

        } catch (Exception e) {
            System.err.println("Lỗi khi kiểm tra yêu cầu thiết bị " + udid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Tạo options tối ưu cho từng loại thiết bị
     */
    private UiAutomator2Options createOptimizedOptions(String udid, int systemPort) {
        boolean isUSBDevice = !udid.contains(":");

        UiAutomator2Options options = new UiAutomator2Options()
                .setDeviceName("Android Device")
                .setUdid(udid)
                .setAppPackage(TIKTOK_PACKAGE)
                .setAppActivity(TIKTOK_ACTIVITY)
                .setSystemPort(systemPort)
                .setNoReset(true)
                .setAutoGrantPermissions(true)
                .setSkipDeviceInitialization(false) // Để false cho USB devices
                .setSkipServerInstallation(false)   // Để false cho USB devices
                .setNewCommandTimeout(Duration.ofSeconds(120)) // Tăng timeout
                .setUiautomator2ServerLaunchTimeout(Duration.ofSeconds(90))
                .setUiautomator2ServerInstallTimeout(Duration.ofSeconds(90))
                .setFullReset(false);

        // Cấu hình đặc biệt cho USB devices
        if (isUSBDevice) {
            options.setCapability("appium:androidDeviceReadyTimeout", 90000);
            options.setCapability("appium:adbExecTimeout", 90000);
            options.setCapability("appium:androidInstallTimeout", 90000);
        } else {
            options.setCapability("appium:androidDeviceReadyTimeout", 60000);
            options.setCapability("appium:adbExecTimeout", 60000);
            options.setCapability("appium:androidInstallTimeout", 60000);
        }

        // Các capabilities chung
        options.setCapability("appium:disableWindowAnimation", true);
        options.setCapability("appium:waitForIdleTimeout", 0);
        options.setCapability("appium:androidDeviceSocket", "tiktok_automation_" + systemPort);
        options.setCapability("appium:ensureWebviewsHavePages", true);
        options.setCapability("appium:webviewDevtoolsPort", 9222 + (systemPort - 8200));
        options.setCapability("appium:dontStopAppOnReset", true);
        options.setCapability("appium:waitForSelectorTimeout", 15000);
        options.setCapability("appium:waitForLaunchTimeout", 90000);
        options.setCapability("appium:networkConnectionEnabled", true);
        options.setCapability("appium:allowTestPackages", true);
        options.setCapability("appium:enforceAppiumPrefix", true);

        return options;
    }

    /**
     * In chi tiết lỗi và gợi ý khắc phục
     */
    private void printDetailedError(Exception e, String udid, boolean isUSBDevice) {
        System.err.println("\n=== CHI TIẾT LỖI VÀ HƯỚNG KHẮC PHỤC ===");
        System.err.println("Thiết bị: " + udid + (isUSBDevice ? " (USB)" : " (WiFi)"));
        System.err.println("Lỗi cuối: " + (e != null ? e.getMessage() : "Unknown"));

        System.err.println("\n🔍 NGUYÊN NHÂN CÓ THỂ:");
        if (isUSBDevice) {
            System.err.println("1. Cáp USB không ổn định hoặc bị lỏng");
            System.err.println("2. Driver USB thiết bị chưa được cài đúng");
            System.err.println("3. Windows Power Management tắt USB port");
            System.err.println("4. UIAutomator2 không thể khởi tạo trên thiết bị");
            System.err.println("5. Port system bị xung đột");
        } else {
            System.err.println("1. Kết nối WiFi không ổn định");
            System.err.println("2. Thiết bị tự động ngắt kết nối ADB qua WiFi");
            System.err.println("3. Firewall chặn kết nối");
        }

        System.err.println("\n🛠️ CÁCH KHẮC PHỤC:");
        System.err.println("1. Khởi động lại Appium server");
        System.err.println("2. Chạy: adb kill-server && adb start-server");
        System.err.println("3. Khởi động lại thiết bị");
        if (isUSBDevice) {
            System.err.println("4. Thử cáp USB khác hoặc cổng USB khác");
            System.err.println("5. Vào Device Manager kiểm tra driver");
            System.err.println("6. Tắt USB selective suspend trong Power Options");
        }
        System.err.println("========================\n");
    }

    public boolean isConnected() {
        return isConnected && driver != null;
    }

    public void followUsers() {
        if (!isConnected()) {
            System.out.println("❌ Chưa kết nối với thiết bị!");
            return;
        }

        System.out.println("🚀 Bắt đầu follow " + userIds.size() + " người dùng...");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        for (int i = 0; i < userIds.size(); i++) {
            String userId = userIds.get(i);
            System.out.println(String.format("📱 [%d/%d] Đang xử lý: @%s", i+1, userIds.size(), userId));

            try {
                // Mở profile người dùng
                driver.get("https://www.tiktok.com/@" + userId);
                safeSleep(delayTime);

                // Thử nhiều cách tìm nút Follow
                WebElement followButton = findFollowButton(wait);

                if (followButton != null) {
                    String buttonText = followButton.getText();
                    System.out.println("🔍 Trạng thái nút: " + buttonText);

                    if ("Follow".equals(buttonText)) {
                        followButton.click();
                        System.out.println("✅ Đã click Follow cho @" + userId);
                        safeSleep(delayTime);

                        // Swipe để refresh
                        swipeDown();
                        safeSleep(1000);

                        // Kiểm tra kết quả
                        WebElement buttonAfter = findFollowButton(wait);
                        if (buttonAfter != null && "Follow".equals(buttonAfter.getText())) {
                            System.out.println("⚠️ Vẫn còn nút Follow, có thể follow không thành công");
                        } else {
                            System.out.println("✅ Follow thành công @" + userId);
                        }
                    } else {
                        System.out.println("ℹ️ Đã follow hoặc không thể follow @" + userId + " (nút: " + buttonText + ")");
                    }
                } else {
                    System.out.println("❌ Không tìm thấy nút Follow cho @" + userId);
                }

            } catch (Exception e) {
                System.err.println("❌ Lỗi khi xử lý @" + userId + ": " + e.getMessage());
            }

            // Nghỉ giữa các lần follow
            if (i < userIds.size() - 1) {
                System.out.println("⏳ Nghỉ " + delayTime + "ms...");
                safeSleep(delayTime);
            }
        }

        System.out.println("🎉 Hoàn thành xử lý tất cả người dùng!");
    }

    /**
     * Tìm nút Follow với nhiều strategy khác nhau
     */
    private WebElement findFollowButton(WebDriverWait wait) {
        // Strategy 1: Tìm theo text đơn giản
        try {
            return wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//android.widget.TextView[@text='Follow']")));
        } catch (Exception e1) {
            // Strategy 2: Tìm theo resource-id
            try {
                return driver.findElement(By.xpath("//android.widget.TextView[@resource-id='com.ss.android.ugc.trill:id/dq0' and @text='Follow']"));
            } catch (Exception e2) {
                // Strategy 3: Tìm theo partial text
                try {
                    return driver.findElement(By.xpath("//android.widget.TextView[contains(@text, 'Follow')]"));
                } catch (Exception e3) {
                    // Strategy 4: Tìm button với text Follow
                    try {
                        return driver.findElement(By.xpath("//android.widget.Button[@text='Follow']"));
                    } catch (Exception e4) {
                        return null;
                    }
                }
            }
        }
    }

    private void swipeDown() {
        try {
            int screenHeight = driver.manage().window().getSize().getHeight();
            int screenWidth = driver.manage().window().getSize().getWidth();

            int startX = screenWidth / 2;
            int startY = (int) (screenHeight * 0.3);
            int endX = screenWidth / 2;
            int endY = (int) (screenHeight * 0.7);

            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
            Sequence swipe = new Sequence(finger, 0);

            swipe.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), startX, startY));
            swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
            swipe.addAction(finger.createPointerMove(Duration.ofMillis(500), PointerInput.Origin.viewport(), endX, endY));
            swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

            driver.perform(List.of(swipe));

        } catch (Exception e) {
            System.err.println("Lỗi khi swipe: " + e.getMessage());
        }
    }

    public void close() {
        if (driver != null) {
            try {
                System.out.println("🔄 Đang đóng kết nối thiết bị...");
                driver.quit();
                isConnected = false;
                System.out.println("✅ Đã đóng kết nối");
            } catch (Exception e) {
                System.err.println("Lỗi khi đóng driver: " + e.getMessage());
            }
        }
    }

    private void safeSleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Chương trình đang thoát...");
            close();
            System.exit(0);
        }
    }
}