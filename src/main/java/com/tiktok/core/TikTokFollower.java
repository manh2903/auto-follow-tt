package com.tiktok.core;

import com.tiktok.util.DeviceConnector;
import com.tiktok.util.LogCallback;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class TikTokFollower {
    private final DeviceConnector deviceConnector;
    private final List<String> userIds;
    private final long delayTime;
    private final LogCallback logCallback;

    public TikTokFollower(String userIdsFilePath, String deviceId, long delayTime, LogCallback logCallback) {
        this.deviceConnector = new DeviceConnector(deviceId);
        this.deviceConnector.setLogCallback(logCallback);
        this.userIds = readUserIds(userIdsFilePath);
        this.delayTime = delayTime;
        this.logCallback = logCallback;

        // Thêm shutdown hook để xử lý khi người dùng nhấn Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Đang thoát chương trình...");
            close();
            log("Đã thoát chương trình.");
        }));
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
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
            log("Lỗi khi đọc file: " + e.getMessage());
        }
        return ids;
    }

    public void connectToDevice() {
        deviceConnector.connect();
    }

    public void followUsers() {
        if (!deviceConnector.isConnected()) {
            log("❌ Chưa kết nối với thiết bị!");
            return;
        }

        AndroidDriver driver = deviceConnector.getDriver();
        log("🚀 Bắt đầu follow " + userIds.size() + " người dùng...");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        for (int i = 0; i < userIds.size(); i++) {
            String userId = userIds.get(i);
            log(String.format("📱 [%d/%d] Đang xử lý: @%s", i+1, userIds.size(), userId));

            try {
                // Mở profile người dùng
                driver.get("https://www.tiktok.com/@" + userId);
                deviceConnector.safeSleep(delayTime);

                // Thử nhiều cách tìm nút Follow
                WebElement followButton = findFollowButton(driver, wait);

                if (followButton != null) {
                    String buttonText = followButton.getText();
                    log("🔍 Trạng thái nút: " + buttonText);

                    if ("Follow".equals(buttonText)) {
                        followButton.click();
                        log("✅ Đã click Follow cho @" + userId);
                        deviceConnector.safeSleep(delayTime);

                        // Swipe để refresh
                        swipeDown(driver);
                        deviceConnector.safeSleep(1000);

                        // Kiểm tra kết quả
                        WebElement buttonAfter = findFollowButton(driver, wait);
                        if (buttonAfter != null && "Follow".equals(buttonAfter.getText())) {
                            log("⚠️ Vẫn còn nút Follow, có thể follow không thành công");
                        } else {
                            log("✅ Follow thành công @" + userId);
                        }
                    } else {
                        log("ℹ️ Đã follow hoặc không thể follow @" + userId + " (nút: " + buttonText + ")");
                    }
                } else {
                    log("❌ Không tìm thấy nút Follow cho @" + userId);
                }

            } catch (Exception e) {
                log("❌ Lỗi khi xử lý @" + userId + ": " + e.getMessage());
            }

            // Nghỉ giữa các lần follow
            if (i < userIds.size() - 1) {
                log("⏳ Nghỉ " + delayTime + "ms...");
                deviceConnector.safeSleep(delayTime);
            }
        }

        log("🎉 Hoàn thành xử lý tất cả người dùng!");
    }

    private WebElement findFollowButton(AndroidDriver driver, WebDriverWait wait) {
        // Strategy 1: Tìm theo text đơn giản
        try {
            return driver.findElement(By.xpath("//android.widget.TextView[@resource-id='com.ss.android.ugc.trill:id/dq0' and @text='Follow']"));
        } catch (Exception e1) {
            return null;
        }
    }

    private void swipeDown(AndroidDriver driver) {
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
            log("Lỗi khi swipe: " + e.getMessage());
        }
    }

    public void close() {
        deviceConnector.close();
    }
}