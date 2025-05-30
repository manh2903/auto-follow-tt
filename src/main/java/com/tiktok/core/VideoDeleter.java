package com.tiktok.core;

import com.tiktok.util.LogCallback;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;

public class VideoDeleter {
    private final AndroidDriver driver;
    private final LogCallback logCallback;
    private final WebDriverWait wait;
    private static final Pattern VIEW_COUNT_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?[KMB]?)");

    public VideoDeleter(AndroidDriver driver, LogCallback logCallback) {
        this.driver = driver;
        this.logCallback = logCallback;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    public void deleteLowViewVideos(int viewThreshold) {
        try {
            log("🚀 Bắt đầu xóa video với ngưỡng: " + viewThreshold + " view");

            // Mở profile
            openProfile();

            // Click vào video đầu tiên
            if (!clickFirstVideo()) {
                log("❌ Không thể mở video đầu tiên");
                return;
            }

            int deletedCount = 0;
            int processedCount = 0;
            int consecutiveErrors = 0;
            final int MAX_CONSECUTIVE_ERRORS = 5;
            final int MAX_PROCESSED_VIDEOS = 50; // Giới hạn để tránh vòng lặp vô tận

            while (processedCount < MAX_PROCESSED_VIDEOS && consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                try {
                    processedCount++;
                    log("🔍 Kiểm tra video thứ " + processedCount);

                    // Đợi video load
                    Thread.sleep(2000);

                    // Kiểm tra lượng view của video hiện tại
                    int currentViewCount = getCurrentVideoViewCount();

                    if (currentViewCount == -1) {
                        log("❌ Không thể đọc được lượng view, chuyển video tiếp theo");
                        swipeToNextVideo();
                        consecutiveErrors++;
                        continue;
                    }

                    log("📊 Video hiện tại có " + currentViewCount + " view (ngưỡng: " + viewThreshold + ")");

                    if (currentViewCount < viewThreshold) {
                        // Xóa video này
                        log("🎯 Video có view thấp, tiến hành xóa");

                        if (deleteCurrentVideo()) {
                            deletedCount++;
                            consecutiveErrors = 0; // Reset counter khi thành công
                            log("✅ Đã xóa video thành công! Tổng đã xóa: " + deletedCount);

                            // Sau khi xóa, đợi 2s để video tiếp theo tự động load
                            Thread.sleep(2000);
                            log("⏳ Đang đợi video tiếp theo tự động load...");
                        } else {
                            log("❌ Xóa video thất bại, chuyển video tiếp theo");
                            swipeToNextVideo();
                            consecutiveErrors++;
                        }
                    } else {
                        // View cao hơn ngưỡng, vuốt lên để load video tiếp theo
                        log("📈 Video có view cao, chuyển sang video tiếp theo");
                        swipeToNextVideo();
                        consecutiveErrors = 0; // Reset khi thành công
                    }

                } catch (Exception e) {
                    log("❌ Lỗi khi xử lý video " + processedCount + ": " + e.getMessage());
                    consecutiveErrors++;

                    // Thử chuyển video tiếp theo
                    try {
                        swipeToNextVideo();
                    } catch (Exception swipeError) {
                        log("❌ Không thể chuyển video tiếp theo: " + swipeError.getMessage());
                    }
                }
            }

            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                log("⚠️ Dừng do quá nhiều lỗi liên tiếp (" + consecutiveErrors + " lỗi)");
            }

            if (processedCount >= MAX_PROCESSED_VIDEOS) {
                log("⚠️ Đã xử lý tối đa " + MAX_PROCESSED_VIDEOS + " video");
            }

            log("🎉 Hoàn thành! Đã kiểm tra " + processedCount + " video, xóa " + deletedCount + " video có view thấp");

        } catch (Exception e) {
            log("❌ Lỗi tổng quát: " + e.getMessage());
        }
    }

    private boolean clickFirstVideo() {
        try {
            log("🔄 Tìm và click video đầu tiên trên profile");

            // Tìm video đầu tiên bằng cover
            List<WebElement> videoCovres = driver.findElements(
                    By.xpath("//android.widget.ImageView[@resource-id='com.ss.android.ugc.trill:id/cover']"));

            if (!videoCovres.isEmpty()) {
                videoCovres.get(0).click();
                log("✅ Đã click vào video đầu tiên");
                return true;
            }

            // Backup: tìm bằng cách khác
            List<WebElement> videoElements = driver.findElements(
                    By.xpath("//android.view.ViewGroup[contains(@resource-id, 'item')]"));

            if (!videoElements.isEmpty()) {
                videoElements.get(0).click();
                log("✅ Đã click vào video đầu tiên (backup method)");
                return true;
            }

            log("❌ Không tìm thấy video nào trên profile");
            return false;

        } catch (Exception e) {
            log("❌ Lỗi khi click video đầu tiên: " + e.getMessage());
            return false;
        }
    }

    private int getCurrentVideoViewCount() {
        try {
            // Tìm element chứa view count với resource-id mới
            List<WebElement> viewElements = driver.findElements(
                    By.xpath("//android.widget.TextView[@resource-id='com.ss.android.ugc.trill:id/vb2']"));

            if (viewElements.isEmpty()) {
                log("❌ Không tìm thấy element view count");
                return -1;
            }

            String viewText = viewElements.get(0).getText();
            int viewCount = parseViewCount(viewText);

            log("📊 Đọc được view count: " + viewText + " -> " + viewCount);
            return viewCount;

        } catch (Exception e) {
            log("❌ Lỗi khi đọc view count: " + e.getMessage());
            return -1;
        }
    }

    private boolean deleteCurrentVideo() {
        try {
            log("🗑️ Bắt đầu xóa video hiện tại");

            // Tìm và click nút menu (...)
            if (!clickMenuButton()) {
                log("❌ Không thể mở menu");
                return false;
            }

            // Click delete button
            if (!clickDeleteButton()) {
                log("❌ Không thể click nút xóa");
                return false;
            }

            log("✅ Đã xóa video thành công");
            return true;

        } catch (Exception e) {
            log("❌ Lỗi khi xóa video: " + e.getMessage());
            return false;
        }
    }

    private void swipeToNextVideo() {
        try {
            log("👆 Vuốt lên để chuyển video tiếp theo");

            int screenHeight = driver.manage().window().getSize().getHeight();
            int screenWidth = driver.manage().window().getSize().getWidth();

            int startX = screenWidth / 2;
            int startY = (int) (screenHeight * 0.8); // Bắt đầu từ 80% màn hình
            int endY = (int) (screenHeight * 0.2);   // Kết thúc ở 20% màn hình

            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
            Sequence swipe = new Sequence(finger, 1);

            swipe.addAction(finger.createPointerMove(Duration.ofMillis(0),
                    PointerInput.Origin.viewport(), startX, startY));
            swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
            swipe.addAction(finger.createPointerMove(Duration.ofMillis(600),
                    PointerInput.Origin.viewport(), startX, endY));
            swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

            driver.perform(Arrays.asList(swipe));

            // Đợi video mới load
            Thread.sleep(1500);
            log("✅ Đã vuốt lên video tiếp theo");

        } catch (Exception e) {
            log("❌ Lỗi khi vuốt chuyển video: " + e.getMessage());
            throw new RuntimeException("Không thể chuyển video tiếp theo", e);
        }
    }

    private void openProfile() throws InterruptedException {
        try {
            // Click vào tab Profile
            WebElement profileTab = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//android.widget.FrameLayout[@content-desc='Hồ sơ']/android.widget.ImageView")));
            profileTab.click();

            // Đợi profile load xong
            Thread.sleep(3000);

            log("✅ Đã mở profile thành công");
        } catch (Exception e) {
            log("❌ Không thể mở profile: " + e.getMessage());
            throw e;
        }
    }

    private boolean clickMenuButton() {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log("🔄 Thử tìm nút menu (lần " + attempt + "/" + maxAttempts + ")");

                // Phương pháp 1: Tìm bằng ID chính xác
                WebElement menuButton = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//android.widget.ImageView[@resource-id='com.ss.android.ugc.trill:id/q3a']")));
                menuButton.click();
                Thread.sleep(1000);
                log("✅ Đã click nút menu (phương pháp 1)");
                return true;

            } catch (Exception e) {
                log("❌ Phương pháp 1 thất bại: " + e.getMessage());

                try {
                    // Phương pháp 2: Tìm trong frame
                    WebElement frame = driver.findElement(
                            By.xpath("//android.widget.FrameLayout[@resource-id='com.ss.android.ugc.trill:id/t7r']"));
                    WebElement menuButton = frame.findElement(By.xpath(".//android.widget.ImageView"));
                    menuButton.click();
                    Thread.sleep(1000);
                    log("✅ Đã click nút menu (phương pháp 2)");
                    return true;
                } catch (Exception e2) {
                    log("❌ Phương pháp 2 thất bại: " + e2.getMessage());

                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(2000); // Đợi lâu hơn
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        }
        return false;
    }

    private boolean clickDeleteButton() {
        try {
            // Đợi menu mở
            Thread.sleep(1000);
            log("⏳ Đã đợi menu mở");

            // Tìm RecyclerView với timeout dài hơn
            WebElement recyclerView = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//androidx.recyclerview.widget.RecyclerView[@resource-id='com.ss.android.ugc.trill:id/v3']")));

            log("✅ Tìm thấy RecyclerView, bắt đầu scroll");

            // Thực hiện scroll để tìm nút xóa
            boolean scrollResult = performHorizontalScrollToFindDelete(recyclerView);

            if (!scrollResult) {
                log("❌ Không tìm thấy nút Xóa sau khi scroll");
                return false;
            }

            // Tìm và click nút xóa
            WebElement deleteButton = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//android.widget.Button[@content-desc='Xóa']")));
            deleteButton.click();
            log("✅ Đã click nút xóa");

            // Đợi và click confirm
            Thread.sleep(2000);
            WebElement confirmButton = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//android.view.ViewGroup[@resource-id='com.ss.android.ugc.trill:id/e1t']")));
            confirmButton.click();
            log("✅ Đã click confirm xóa");

            return true;

        } catch (Exception e) {
            log("❌ Không thể thực hiện xóa video: " + e.getMessage());
            return false;
        }
    }

    private boolean performHorizontalScrollToFindDelete(WebElement element) {
        try {
            int elementX = element.getLocation().getX();
            int elementY = element.getLocation().getY();
            int elementWidth = element.getSize().getWidth();
            int elementHeight = element.getSize().getHeight();

            int startX = elementX + elementWidth - 50;
            int endX = elementX + 50;
            int y = elementY + (elementHeight / 2);

            log("📍 RecyclerView: x=" + elementX + ", y=" + elementY + ", w=" + elementWidth + ", h=" + elementHeight);

            int maxScrollAttempts = 8;

            for (int scrollCount = 1; scrollCount <= maxScrollAttempts; scrollCount++) {
                log("🔄 Scroll lần " + scrollCount + "/" + maxScrollAttempts);

                try {
                    scrollWithW3CActions(startX, y, endX, y);
                    Thread.sleep(1500); // Đợi animation

                    // Kiểm tra nút Xóa
                    List<WebElement> deleteButtons = driver.findElements(
                            By.xpath("//android.widget.Button[@content-desc='Xóa']"));

                    if (!deleteButtons.isEmpty() && deleteButtons.get(0).isDisplayed()) {
                        log("✅ Tìm thấy nút Xóa sau " + scrollCount + " lần scroll!");
                        return true;
                    }

                    Thread.sleep(500);

                } catch (Exception e) {
                    log("❌ Lỗi scroll lần " + scrollCount + ": " + e.getMessage());
                    Thread.sleep(1000);
                }
            }

            // Thử scroll với khoảng cách lớn hơn
            log("🔄 Thử scroll cuối cùng với khoảng cách lớn hơn");
            try {
                scrollWithW3CActions(elementX + elementWidth - 20, y, elementX + 20, y);
                Thread.sleep(2000);

                List<WebElement> deleteButtons = driver.findElements(
                        By.xpath("//android.widget.Button[@content-desc='Xóa']"));

                if (!deleteButtons.isEmpty() && deleteButtons.get(0).isDisplayed()) {
                    log("✅ Tìm thấy nút Xóa sau scroll cuối cùng!");
                    return true;
                }
            } catch (Exception e) {
                log("❌ Scroll cuối cùng thất bại: " + e.getMessage());
            }

            return false;

        } catch (Exception e) {
            log("❌ Lỗi khi scroll tìm nút xóa: " + e.getMessage());
            return false;
        }
    }

    private void scrollWithW3CActions(int startX, int startY, int endX, int endY) {
        try {
            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
            Sequence swipe = new Sequence(finger, 1);

            swipe.addAction(finger.createPointerMove(Duration.ofMillis(0),
                    PointerInput.Origin.viewport(), startX, startY));
            swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
            swipe.addAction(finger.createPointerMove(Duration.ofMillis(800),
                    PointerInput.Origin.viewport(), endX, endY));
            swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

            driver.perform(Arrays.asList(swipe));

        } catch (Exception e) {
            log("❌ W3C Actions scroll thất bại: " + e.getMessage());
            throw e;
        }
    }

    private int parseViewCount(String viewText) {
        try {
            if (viewText == null || viewText.trim().isEmpty()) {
                return 0;
            }

            viewText = viewText.trim().toLowerCase();

            // Số thuần
            if (viewText.matches("\\d+")) {
                return Integer.parseInt(viewText);
            }

            // Pattern với K, M, B
            Matcher matcher = VIEW_COUNT_PATTERN.matcher(viewText);
            if (matcher.find()) {
                String count = matcher.group(1);
                count = count.replace(",", ".");

                if (count.endsWith("k")) {
                    return (int) (Double.parseDouble(count.replace("k", "")) * 1000);
                } else if (count.endsWith("m")) {
                    return (int) (Double.parseDouble(count.replace("m", "")) * 1000000);
                } else if (count.endsWith("b")) {
                    return (int) (Double.parseDouble(count.replace("b", "")) * 1000000000);
                } else {
                    return Integer.parseInt(count.replace(".", ""));
                }
            }

            // Fallback: số đầu tiên
            Pattern numberPattern = Pattern.compile("(\\d+)");
            Matcher numberMatcher = numberPattern.matcher(viewText);
            if (numberMatcher.find()) {
                return Integer.parseInt(numberMatcher.group(1));
            }

            return 0;

        } catch (Exception e) {
            log("❌ Không parse được view count: '" + viewText + "' - " + e.getMessage());
            return 0;
        }
    }

    public void deleteLowViewVideosOptimized(int viewThreshold) {
        // Method này giờ chỉ gọi method chính vì logic đã được tối ưu
        deleteLowViewVideos(viewThreshold);
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
    }
}