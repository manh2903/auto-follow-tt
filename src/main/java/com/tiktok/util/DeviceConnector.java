package com.tiktok.util;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceConnector {
    private static final String TIKTOK_PACKAGE = "com.ss.android.ugc.trill";
    private static final String TIKTOK_ACTIVITY = "com.ss.android.ugc.trill.splash.SplashActivity";
    private static final String TIKTOK_MAIN_ACTIVITY = "com.ss.android.ugc.aweme.main.MainActivity";

    // Static map để quản lý port cho từng thiết bị
    private static final Map<String, Integer> DEVICE_PORTS = new ConcurrentHashMap<>();
    private static int nextPort = 8200;

    private AndroidDriver driver;
    private String deviceId;
    private boolean isConnected = false;
    private LogCallback logCallback;

    public DeviceConnector(String deviceId) {
        this.deviceId = deviceId;
    }

    public void setLogCallback(LogCallback callback) {
        this.logCallback = callback;
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
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
                    devices.add(id);
                }
            }
            if (devices.isEmpty()) {
                System.err.println("Không tìm thấy thiết bị Android nào!");
            }
        } catch (Exception e) {
            System.err.println("Lỗi khi lấy danh sách thiết bị: " + e.getMessage());
        }
        return devices;
    }

    public void connect() {
        try {
            String udid = deviceId;
            if (udid == null) {
                log("❌ Không tìm thấy thiết bị Android nào! Hãy kiểm tra lại kết nối ADB.");
                return;
            }

            log("=== BẮT ĐẦU KẾT NỐI THIẾT BỊ: " + udid + " ===");

            // Bước 1: Validate thiết bị trước khi kết nối
            if (!validateDevice(udid)) {
                log("❌ Thiết bị " + udid + " không hợp lệ, bỏ qua");
                return;
            }

            // Bước 2: Kiểm tra các điều kiện cần thiết
            if (!checkDeviceRequirements(udid)) {
                return;
            }

            // Bước 3: Kiểm tra TikTok một cách thông minh hơn
            boolean tiktokInForeground = isTikTokInForeground(udid);
            boolean tiktokRunning = !tiktokInForeground ? isTikTokRunning(udid) : true;

            if (!tiktokInForeground) {
                if (tiktokRunning) {
                    log("TikTok đang chạy background, đưa về foreground...");
                    bringTikTokToForeground(udid);
                } else {
                    log("TikTok chưa chạy, đang mở ứng dụng...");
                    openTikTok(udid);
                }
            } else {
                log("✅ TikTok đã ở foreground, không cần mở lại");
            }

            // Bước 4: Chuẩn bị kết nối
            int systemPort = getSystemPortForDevice(udid);
            cleanupPort(systemPort);

            // Bước 5: Nếu là thiết bị USB và đã validate thất bại trước đó, thử restart ADB
            boolean isUSBDevice = !udid.contains(":");
            if (isUSBDevice) {
                log("Phát hiện thiết bị USB, thực hiện các bước chuẩn bị đặc biệt...");
                if (!canStartUIAutomator2(udid)) {
                    restartAdbForDevice(udid);
                    // Validate lại sau khi restart
                    if (!validateDevice(udid)) {
                        log("❌ Thiết bị USB " + udid + " vẫn không ổn định sau restart");
                        return;
                    }
                }
            }

            // Bước 6: Tạo options với cấu hình tối ưu
            UiAutomator2Options options = createOptimizedOptions(udid, systemPort);

            // Bước 7: Thử kết nối với retry logic cải tiến
            log("Đang kết nối tới Appium server với port " + systemPort + "...");

            int maxRetries = isUSBDevice ? 6 : 4; // USB device cần nhiều lần thử hơn
            int retryCount = 0;
            Exception lastException = null;

            while (retryCount < maxRetries) {
                try {
                    driver = new AndroidDriver(new URL("http://127.0.0.1:4723"), options);
                    isConnected = true;
                    log("✅ Đã kết nối thành công với thiết bị " + udid);

                    // Test connection bằng cách lấy package hiện tại
                    String currentPackage = driver.getCurrentPackage();
                    log("Package hiện tại: " + currentPackage);

                    // Đảm bảo TikTok đang được focus (chỉ khi cần thiết)
                    if (!TIKTOK_PACKAGE.equals(currentPackage)) {
                        log("Đang chuyển focus về TikTok...");
                        bringTikTokToForeground(udid);
                        safeSleep(2000);
                    }

                    return;

                } catch (Exception e) {
                    lastException = e;
                    retryCount++;

                    log(String.format("❌ Lần thử %d/%d thất bại cho thiết bị %s",
                            retryCount, maxRetries, udid));
                    log("Lỗi: " + e.getMessage());

                    if (retryCount < maxRetries) {
                        // Tăng thời gian delay theo từng lần thử
                        long delay = 3000 + (2000 * retryCount);
                        log("⏳ Đợi " + delay + "ms trước khi thử lại...");
                        Thread.sleep(delay);

                        // Các bước khắc phục theo lần thử
                        if (retryCount == 2) {
                            log("🔄 Thực hiện cleanup UIAutomator2...");
                            canStartUIAutomator2(udid);
                        } else if (retryCount == 3 && isUSBDevice) {
                            log("🔄 Restart ADB cho thiết bị USB...");
                            restartAdbForDevice(udid);
                        } else if (retryCount == 4) {
                            // Thử port khác
                            systemPort = getSystemPortForDevice(udid + "_retry");
                            options.setSystemPort(systemPort);
                            log("🔄 Thử port mới: " + systemPort);
                        }
                    }
                }
            }

            // Nếu hết lần thử
            log("❌ KHÔNG THỂ KẾT NỐI với thiết bị " + udid + " sau " + maxRetries + " lần thử");
            printDetailedError(lastException, udid, isUSBDevice);

        } catch (Exception e) {
            log("=== LỖI NGHIÊM TRỌNG KHI KẾT NỐI THIẾT BỊ ===");
            log("Chi tiết lỗi: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Hàm kiểm tra nhanh TikTok có ở foreground không
    private boolean isTikTokInForeground(String udid) {
        try {
            log("Đang kiểm tra TikTok foreground...");

            // Lệnh đơn giản và nhanh để kiểm tra app hiện tại
            Process process = Runtime.getRuntime().exec("adb -s " + udid + " shell dumpsys window windows | grep -E 'mCurrentFocus'");
            process.waitFor();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();

            if (line != null && line.contains(TIKTOK_PACKAGE)) {
                log("✅ TikTok đang ở foreground: " + line.trim());
                return true;
            }

            // Kiểm tra thêm bằng focused activity
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell dumpsys activity activities | grep -E 'mCurrentFocus|mFocusedActivity'");
            process.waitFor();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while ((line = reader.readLine()) != null) {
                if (line.contains(TIKTOK_PACKAGE)) {
                    log("✅ TikTok có focused activity: " + line.trim());
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            log("Lỗi kiểm tra foreground: " + e.getMessage());
            return false;
        }
    }

    // Hàm kiểm tra chi tiết trạng thái TikTok
    private boolean isTikTokRunning(String udid) {
        try {
            log("Đang kiểm tra trạng thái TikTok chi tiết...");

            // Phương pháp 1: Kiểm tra running processes
            Process process = Runtime.getRuntime().exec("adb -s " + udid + " shell ps | grep " + TIKTOK_PACKAGE);
            process.waitFor();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String processLine = reader.readLine();

            if (processLine != null && processLine.contains(TIKTOK_PACKAGE)) {
                log("TikTok process đang chạy: " + processLine.trim());

                // Kiểm tra xem có phải là background process không
                if (processLine.contains("S") || processLine.contains("R")) {
                    // Kiểm tra thêm bằng cách xem package có trong activity stack không
                    process = Runtime.getRuntime().exec("adb -s " + udid + " shell dumpsys activity activities | grep -A 20 'Running activities'");
                    process.waitFor();

                    reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains(TIKTOK_PACKAGE)) {
                            if (line.contains("state=RESUMED")) {
                                log("✅ TikTok trong RESUMED state");
                                return true;
                            } else if (line.contains("state=PAUSED") || line.contains("state=STOPPED")) {
                                log("TikTok đang chạy nhưng ở background (PAUSED/STOPPED)");
                                return true; // Vẫn coi là đang chạy
                            }
                        }
                    }

                    log("TikTok đang chạy background");
                    return true;
                }
            }

            // Phương pháp 2: Kiểm tra recent tasks
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell dumpsys activity recents | grep " + TIKTOK_PACKAGE);
            process.waitFor();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String recentTask = reader.readLine();

            if (recentTask != null && recentTask.contains(TIKTOK_PACKAGE)) {
                log("TikTok có trong recent tasks nhưng có thể không active");
                return false; // Có trong recent nhưng chưa chắc đang chạy
            }

            log("❌ TikTok không đang chạy");
            return false;

        } catch (Exception e) {
            log("❌ Lỗi khi kiểm tra trạng thái TikTok: " + e.getMessage());
            return false;
        }
    }

    // Hàm mới để đưa TikTok về foreground thay vì mở lại
    private boolean bringTikTokToForeground(String udid) {
        try {
            log("Đang đưa TikTok về foreground...");

            // Sử dụng intent để đưa app về foreground (không restart)
            String command = "adb -s " + udid + " shell am start -S -n " + TIKTOK_PACKAGE + "/" + TIKTOK_MAIN_ACTIVITY;
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log("✅ Đã gửi lệnh đưa TikTok về foreground");
                safeSleep(2000); // Đợi ngắn hơn vì app đã chạy rồi

                // Kiểm tra xem đã về foreground chưa
                if (isTikTokInForeground(udid)) {
                    log("✅ TikTok đã được đưa về foreground thành công");
                    return true;
                } else {
                    log("⚠️ TikTok chưa về foreground, thử lại...");
                }
            }

            // Fallback: thử mở bằng cách khác
            log("Thử đưa về foreground bằng launcher intent...");
            command = "adb -s " + udid + " shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n " + TIKTOK_PACKAGE + "/" + TIKTOK_MAIN_ACTIVITY;
            process = Runtime.getRuntime().exec(command);
            process.waitFor();
            safeSleep(2000);

            return isTikTokInForeground(udid);

        } catch (Exception e) {
            log("❌ Lỗi khi đưa TikTok về foreground: " + e.getMessage());
            return false;
        }
    }

    private boolean openTikTok(String udid) {
        try {
            log("Đang mở TikTok bằng lệnh ADB...");

            // Sử dụng lệnh am start để mở TikTok với main activity
            String command = "adb -s " + udid + " shell am start -n " + TIKTOK_PACKAGE + "/" + TIKTOK_MAIN_ACTIVITY;
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log("✅ Đã gửi lệnh mở TikTok thành công");

                // Đợi một chút để ứng dụng khởi động
                safeSleep(5000);

                // Kiểm tra xem TikTok đã mở chưa
                boolean isOpened = isTikTokInForeground(udid);
                if (isOpened) {
                    log("✅ TikTok đã được mở thành công");
                    return true;
                } else {
                    log("⚠️ TikTok đã được gửi lệnh mở nhưng chưa ở foreground");

                    // Thử lệnh khác với splash activity
                    log("Thử mở bằng splash activity...");
                    command = "adb -s " + udid + " shell am start -n " + TIKTOK_PACKAGE + "/" + TIKTOK_ACTIVITY;
                    process = Runtime.getRuntime().exec(command);
                    process.waitFor();
                    safeSleep(3000);

                    return isTikTokInForeground(udid);
                }
            } else {
                // Đọc error output
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                String errorLine;
                StringBuilder errorMessage = new StringBuilder();
                while ((errorLine = errorReader.readLine()) != null) {
                    errorMessage.append(errorLine).append("\n");
                }

                log("❌ Lỗi khi mở TikTok (exit code: " + exitCode + "): " + errorMessage.toString());
                return false;
            }

        } catch (Exception e) {
            log("❌ Lỗi khi mở TikTok: " + e.getMessage());
            return false;
        }
    }

    private synchronized int getSystemPortForDevice(String udid) {
        if (!DEVICE_PORTS.containsKey(udid)) {
            DEVICE_PORTS.put(udid, nextPort++);
            log("Gán port " + (nextPort - 1) + " cho thiết bị " + udid);
        }
        return DEVICE_PORTS.get(udid);
    }

    private boolean validateDevice(String udid) {
        try {
            log("Đang validate thiết bị: " + udid);

            // Kiểm tra trạng thái thiết bị
            Process process = Runtime.getRuntime().exec("adb -s " + udid + " shell getprop ro.boot.serialno");
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String serialno = reader.readLine();

            if (serialno == null || serialno.trim().isEmpty()) {
                log("❌ Thiết bị " + udid + " không phản hồi getprop");
                return false;
            }

            // Test ADB connection cơ bản
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell echo 'test'");
            process.waitFor();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String response = reader.readLine();

            if (!"test".equals(response)) {
                log("❌ ADB connection không ổn định cho thiết bị " + udid);
                return false;
            }

            // Kiểm tra trạng thái boot
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell getprop sys.boot_completed");
            process.waitFor();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String bootCompleted = reader.readLine();

            if (!"1".equals(bootCompleted)) {
                log("❌ Thiết bị " + udid + " chưa boot hoàn tất");
                return false;
            }

            log("✅ Thiết bị " + udid + " validation thành công");
            return true;

        } catch (Exception e) {
            log("❌ Lỗi validate thiết bị " + udid + ": " + e.getMessage());
            return false;
        }
    }

    private void restartAdbForDevice(String udid) {
        try {
            log("Restart ADB connection cho thiết bị " + udid);

            // Nếu là WiFi device, disconnect và reconnect
            if (udid.contains(":")) {
                Runtime.getRuntime().exec("adb disconnect " + udid).waitFor();
                Thread.sleep(2000);
                Runtime.getRuntime().exec("adb connect " + udid).waitFor();
                Thread.sleep(3000);
            } else {
                // Với USB device, chỉ restart connection cho thiết bị cụ thể
                Runtime.getRuntime().exec("adb -s " + udid + " usb").waitFor();
                Thread.sleep(2000);
                Runtime.getRuntime().exec("adb -s " + udid + " tcpip 5555").waitFor();
                Thread.sleep(2000);
                Runtime.getRuntime().exec("adb -s " + udid + " usb").waitFor();
                Thread.sleep(3000);
            }

        } catch (Exception e) {
            log("❌ Lỗi restart ADB: " + e.getMessage());
        }
    }

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
            log("❌ Không thể kiểm tra UIAutomator2 trên thiết bị " + udid + ": " + e.getMessage());
            return false;
        }
    }

    private void cleanupPort(int port) {
        try {
            // Kiểm tra port có đang được sử dụng không (Windows)
            Process process = Runtime.getRuntime().exec("netstat -ano | findstr :" + port);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();

            if (line != null && line.contains(":" + port)) {
                log("Port " + port + " đang được sử dụng, đang cố gắng giải phóng...");
                // Có thể thêm logic kill process nếu cần
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    private boolean checkDeviceRequirements(String udid) {
        try {
            // Kiểm tra USB Debugging
            Process process = Runtime.getRuntime().exec("adb -s " + udid + " shell settings get global adb_enabled");
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String adbEnabled = reader.readLine();
            if (!"1".equals(adbEnabled)) {
                log("❌ USB Debugging chưa được bật trên thiết bị " + udid);
                return false;
            }

            // Kiểm tra màn hình khóa (simplified check)
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell dumpsys power | grep 'Display Power'");
            process.waitFor();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String powerState = reader.readLine();
            if (powerState != null && powerState.contains("OFF")) {
                log("❌ Màn hình thiết bị " + udid + " đang tắt");
                return false;
            }

            // Kiểm tra TikTok
            process = Runtime.getRuntime().exec("adb -s " + udid + " shell pm list packages | grep com.ss.android.ugc.trill");
            process.waitFor();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String tiktokPackage = reader.readLine();

            if (tiktokPackage == null || !tiktokPackage.contains("com.ss.android.ugc.trill")) {
                log("❌ TikTok chưa được cài đặt trên thiết bị " + udid);
                return false;
            }

            log("✅ TikTok đã được cài đặt trên thiết bị " + udid);
            return true;

        } catch (Exception e) {
            log("❌ Lỗi khi kiểm tra yêu cầu thiết bị " + udid + ": " + e.getMessage());
            return false;
        }
    }

    private void printDetailedError(Exception e, String udid, boolean isUSBDevice) {
        log("\n=== CHI TIẾT LỖI VÀ HƯỚNG KHẮC PHỤC ===");
        log("Thiết bị: " + udid + (isUSBDevice ? " (USB)" : " (WiFi)"));
        log("Lỗi cuối: " + (e != null ? e.getMessage() : "Unknown"));

        log("\n🔍 NGUYÊN NHÂN CÓ THỂ:");
        if (isUSBDevice) {
            log("1. Cáp USB không ổn định hoặc bị lỏng");
            log("2. Driver USB thiết bị chưa được cài đúng");
            log("3. Windows Power Management tắt USB port");
            log("4. UIAutomator2 không thể khởi tạo trên thiết bị");
            log("5. Port system bị xung đột");
        } else {
            log("1. Kết nối WiFi không ổn định");
            log("2. Thiết bị tự động ngắt kết nối ADB qua WiFi");
            log("3. Firewall chặn kết nối");
        }

        log("\n🛠️ CÁCH KHẮC PHỤC:");
        log("1. Khởi động lại Appium server");
        log("2. Chạy: adb kill-server && adb start-server");
        log("3. Khởi động lại thiết bị");
        if (isUSBDevice) {
            log("4. Thử cáp USB khác hoặc cổng USB khác");
            log("5. Vào Device Manager kiểm tra driver");
            log("6. Tắt USB selective suspend trong Power Options");
        }
        log("========================\n");
    }

    public boolean isConnected() {
        return isConnected && driver != null;
    }

    public AndroidDriver getDriver() {
        return driver;
    }

    public void close() {
        if (driver != null) {
            try {
                log("🔄 Đang đóng kết nối thiết bị...");
                try {
                    driver.quit();
                } catch (Exception e) {
                    // Bỏ qua lỗi khi driver đã bị đóng
                    log("Driver đã được đóng trước đó");
                }
                isConnected = false;
                log("✅ Đã đóng kết nối");
            } catch (Exception e) {
                log("❌ Lỗi khi đóng driver: " + e.getMessage());
            } finally {
                driver = null;
            }
        }
    }

    public void safeSleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Chương trình đang thoát...");
            close();
            System.exit(0);
        }
    }

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
}