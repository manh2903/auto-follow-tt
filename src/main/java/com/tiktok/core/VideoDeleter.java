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
import java.util.HashSet;
import java.util.Set;

public class VideoDeleter {
    private final AndroidDriver driver;
    private final LogCallback logCallback;
    private final WebDriverWait wait;
    private static final Pattern VIEW_COUNT_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?[KMB]?)");
    
    // Bộ nhớ để theo dõi video đã xử lý
    private final Set<String> processedVideoIds = new HashSet<>();
    private String lastVideoIdentifier = null;
    private int sameVideoCount = 0;
    private static final int MAX_SAME_VIDEO_COUNT = 3; // Cho phép 3 lần gặp video giống nhau

    public VideoDeleter(AndroidDriver driver, LogCallback logCallback) {
        this.driver = driver;
        this.logCallback = logCallback;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    public void deleteLowViewVideos(int viewThreshold) {
        try {
            log("🚀 Bắt đầu xóa video với ngưỡng: " + viewThreshold + " view");
            
            // Reset tracking variables
            processedVideoIds.clear();
            lastVideoIdentifier = null;
            sameVideoCount = 0;

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
            final int MAX_CONSECUTIVE_ERRORS = 3; // Giảm xuống 3 để phát hiện hết video nhanh hơn
            final int MAX_NO_NEW_VIDEO_COUNT = 5; // Số lần tối đa không có video mới

            while (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                try {
                    processedCount++;
                    log("🔍 Kiểm tra video thứ " + processedCount);

                    // Đợi video load
                    Thread.sleep(2000);

                    // Lấy identifier của video hiện tại
                    String currentVideoId = getCurrentVideoIdentifier();
                    
                    // Kiểm tra xem có phải video mới không
                    if (!isNewVideo(currentVideoId)) {
                        log("⚠️ Đã gặp video này trước đó hoặc hết video, có thể đã hết danh sách");
                        if (sameVideoCount >= MAX_SAME_VIDEO_COUNT) {
                            log("🏁 Đã hết video trong danh sách!");
                            break;
                        }
                        swipeToNextVideo();
                        continue;
                    }

                    // Đánh dấu video đã xử lý
                    processedVideoIds.add(currentVideoId);
                    lastVideoIdentifier = currentVideoId;
                    sameVideoCount = 0; // Reset counter

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

                            // Sau khi xóa, video tiếp theo sẽ tự động load
                            Thread.sleep(2000);
                            log("⏳ Đang đợi video tiếp theo tự động load...");
                            
                            // Kiểm tra xem có còn video không sau khi xóa
                            if (!hasMoreVideos()) {
                                log("🏁 Không còn video nào sau khi xóa!");
                                break;
                            }
                        } else {
                            log("❌ Xóa video thất bại, chuyển video tiếp theo");
                            swipeToNextVideo();
                            consecutiveErrors++;
                        }
                    } else {
                        // View cao hơn ngưỡng, vuốt lên để load video tiếp theo
                        log("📈 Video có view cao, chuyển sang video tiếp theo");
                        if (!swipeToNextVideoWithValidation()) {
                            log("🏁 Không thể chuyển đến video tiếp theo, có thể đã hết danh sách!");
                            break;
                        }
                        consecutiveErrors = 0; // Reset khi thành công
                    }

                } catch (Exception e) {
                    log("❌ Lỗi khi xử lý video " + processedCount + ": " + e.getMessage());
                    consecutiveErrors++;

                    // Thử chuyển video tiếp theo
                    try {
                        if (!swipeToNextVideoWithValidation()) {
                            log("🏁 Không thể chuyển video tiếp theo, kết thúc!");
                            break;
                        }
                    } catch (Exception swipeError) {
                        log("❌ Không thể chuyển video tiếp theo: " + swipeError.getMessage());
                        consecutiveErrors++;
                    }
                }
            }

            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                log("⚠️ Dừng do quá nhiều lỗi liên tiếp (" + consecutiveErrors + " lỗi)");
            }

            log("🎉 Hoàn thành! Đã kiểm tra " + processedCount + " video, xóa " + deletedCount + " video có view thấp");

        } catch (Exception e) {
            log("❌ Lỗi tổng quát: " + e.getMessage());
        }
    }

    /**
     * Lấy identifier duy nhất của video hiện tại
     */
    private String getCurrentVideoIdentifier() {
        try {
            // Thử lấy từ nhiều nguồn để tạo identifier duy nhất
            StringBuilder identifier = new StringBuilder();
            
            // 1. Thử lấy từ view count text
            String viewText = getCurrentVideoViewText();
            if (viewText != null && !viewText.isEmpty()) {
                identifier.append("view:").append(viewText).append(";");
            }
            
            // 2. Thử lấy từ tên tác giả hoặc username
            String author = getCurrentVideoAuthor();
            if (author != null && !author.isEmpty()) {
                identifier.append("author:").append(author).append(";");
            }
            
            // 3. Thử lấy từ mô tả video (một phần)
            String description = getCurrentVideoDescription();
            if (description != null && !description.isEmpty()) {
                // Chỉ lấy 20 ký tự đầu để tránh quá dài
                String shortDesc = description.length() > 20 ? description.substring(0, 20) : description;
                identifier.append("desc:").append(shortDesc).append(";");
            }
            
            // 4. Nếu không có gì, sử dụng timestamp + random
            if (identifier.length() == 0) {
                identifier.append("time:").append(System.currentTimeMillis());
            }
            
            return identifier.toString();
            
        } catch (Exception e) {
            log("❌ Lỗi khi lấy video identifier: " + e.getMessage());
            return "unknown:" + System.currentTimeMillis();
        }
    }

    /**
     * Kiểm tra xem có phải video mới không
     */
    private boolean isNewVideo(String videoId) {
        if (videoId == null || videoId.equals(lastVideoIdentifier)) {
            sameVideoCount++;
            return false;
        }
        
        if (processedVideoIds.contains(videoId)) {
            sameVideoCount++;
            return false;
        }
        
        return true;
    }

    /**
     * Lấy text của view count (không parse)
     */
    private String getCurrentVideoViewText() {
        try {
            String[] possibleResourceIds = {
                    "com.ss.android.ugc.trill:id/vb2",
                    "com.ss.android.ugc.trill:id/v0s",
                    "com.ss.android.ugc.trill:id/tzo"
            };

            for (String resourceId : possibleResourceIds) {
                try {
                    List<WebElement> viewElements = driver.findElements(
                            By.xpath("//android.widget.TextView[@resource-id='" + resourceId + "']"));
                    if (!viewElements.isEmpty()) {
                        return viewElements.get(0).getText();
                    }
                } catch (Exception ignored) {}
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lấy tên tác giả video
     */
    private String getCurrentVideoAuthor() {
        try {
            // Thử các cách khác nhau để lấy tên tác giả
            String[] authorSelectors = {
                    "//android.widget.TextView[contains(@text, '@')]",
                    "//android.widget.TextView[@resource-id='com.ss.android.ugc.trill:id/title']",
                    "//android.widget.TextView[contains(@resource-id, 'author')]"
            };
            
            for (String selector : authorSelectors) {
                try {
                    List<WebElement> elements = driver.findElements(By.xpath(selector));
                    if (!elements.isEmpty()) {
                        String text = elements.get(0).getText();
                        if (text != null && !text.trim().isEmpty()) {
                            return text.trim();
                        }
                    }
                } catch (Exception ignored) {}
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lấy mô tả video
     */
    private String getCurrentVideoDescription() {
        try {
            List<WebElement> descElements = driver.findElements(
                    By.xpath("//android.widget.TextView[contains(@resource-id, 'desc') or contains(@resource-id, 'caption')]"));
            
            if (!descElements.isEmpty()) {
                String desc = descElements.get(0).getText();
                return desc != null ? desc.trim() : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Kiểm tra xem còn có video nào không
     */
    private boolean hasMoreVideos() {
        try {
            // Kiểm tra xem có video player không
            List<WebElement> videoPlayers = driver.findElements(
                    By.xpath("//android.view.ViewGroup[contains(@resource-id, 'video')]"));
            
            if (videoPlayers.isEmpty()) {
                return false;
            }
            
            // Kiểm tra xem có nút play hay loading indicator không
            List<WebElement> playButtons = driver.findElements(
                    By.xpath("//android.widget.ImageView[contains(@resource-id, 'play')]"));
            
            List<WebElement> loadingIndicators = driver.findElements(
                    By.xpath("//android.widget.ProgressBar"));
            
            return !playButtons.isEmpty() || !loadingIndicators.isEmpty() || !videoPlayers.isEmpty();
            
        } catch (Exception e) {
            log("❌ Lỗi khi kiểm tra có còn video không: " + e.getMessage());
            return true; // Mặc định là còn video để tiếp tục
        }
    }

    /**
     * Vuốt đến video tiếp theo với validation
     */
    private boolean swipeToNextVideoWithValidation() {
        try {
            String beforeSwipeId = getCurrentVideoIdentifier();
            
            swipeToNextVideo();
            
            // Đợi video mới load
            Thread.sleep(2000);
            
            String afterSwipeId = getCurrentVideoIdentifier();
            
            // Kiểm tra xem có chuyển được video mới không
            if (beforeSwipeId.equals(afterSwipeId)) {
                log("⚠️ Video không thay đổi sau khi vuốt, có thể đã hết danh sách");
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            log("❌ Lỗi khi vuốt với validation: " + e.getMessage());
            return false;
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
            String[] possibleResourceIds = {
                    "com.ss.android.ugc.trill:id/vb2",
                    "com.ss.android.ugc.trill:id/v0s",
                    "com.ss.android.ugc.trill:id/tzo"
            };

            for (String resourceId : possibleResourceIds) {
                try {
                    log("🔍 Thử tìm view count với resource-id: " + resourceId);
                    List<WebElement> viewElements = driver.findElements(
                            By.xpath("//android.widget.TextView[@resource-id='" + resourceId + "']"));

                    if (!viewElements.isEmpty()) {
                        String viewText = viewElements.get(0).getText();
                        int viewCount = parseViewCount(viewText);
                        log("📊 Đọc được view count: " + viewText + " -> " + viewCount + " (từ " + resourceId + ")");
                        return viewCount;
                    }
                } catch (Exception e) {
                    log("⚠️ Không tìm thấy view count với " + resourceId + ": " + e.getMessage());
                }
            }

            log("🔍 Thử tìm view count theo text pattern");
            List<WebElement> allTextViews = driver.findElements(By.className("android.widget.TextView"));
            for (WebElement textView : allTextViews) {
                try {
                    String text = textView.getText();
                    if (text != null && (text.contains("lượt xem") || text.contains("views") || text.matches(".*[0-9]+[KMB]?.*"))) {
                        int viewCount = parseViewCount(text);
                        log("📊 Đọc được view count từ text: " + text + " -> " + viewCount);
                        return viewCount;
                    }
                } catch (Exception ignored) {}
            }

            log("❌ Không tìm thấy element view count với bất kỳ phương pháp nào");
            return -1;

        } catch (Exception e) {
            log("❌ Lỗi khi đọc view count: " + e.getMessage());
            return -1;
        }
    }

    private boolean deleteCurrentVideo() {
        try {
            log("🗑️ Bắt đầu xóa video hiện tại");

            if (!clickMenuButton()) {
                log("❌ Không thể mở menu");
                return false;
            }

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
            int startY = (int) (screenHeight * 0.8);
            int endY = (int) (screenHeight * 0.2);

            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
            Sequence swipe = new Sequence(finger, 1);

            swipe.addAction(finger.createPointerMove(Duration.ofMillis(0),
                    PointerInput.Origin.viewport(), startX, startY));
            swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
            swipe.addAction(finger.createPointerMove(Duration.ofMillis(600),
                    PointerInput.Origin.viewport(), startX, endY));
            swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

            driver.perform(Arrays.asList(swipe));

            Thread.sleep(1500);
            log("✅ Đã vuốt lên video tiếp theo");

        } catch (Exception e) {
            log("❌ Lỗi khi vuốt chuyển video: " + e.getMessage());
            throw new RuntimeException("Không thể chuyển video tiếp theo", e);
        }
    }

    private void openProfile() throws InterruptedException {
        try {
            WebElement profileTab = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//android.widget.FrameLayout[@content-desc='Hồ sơ']/android.widget.ImageView")));
            profileTab.click();

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

                WebElement menuButton = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//android.widget.ImageView[@resource-id='com.ss.android.ugc.trill:id/q3a']")));
                menuButton.click();
                Thread.sleep(1000);
                log("✅ Đã click nút menu (phương pháp 1)");
                return true;

            } catch (Exception e) {
                log("❌ Phương pháp 1 thất bại: " + e.getMessage());

                try {
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
                            Thread.sleep(2000);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        }
        return false;
    }

    private boolean clickDeleteButton() {
        try {
            Thread.sleep(1000);
            log("⏳ Đã đợi menu mở");

            WebElement recyclerView = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//androidx.recyclerview.widget.RecyclerView[@resource-id='com.ss.android.ugc.trill:id/v3']")));

            log("✅ Tìm thấy RecyclerView, bắt đầu scroll");

            boolean scrollResult = performHorizontalScrollToFindDelete(recyclerView);

            if (!scrollResult) {
                log("❌ Không tìm thấy nút Xóa sau khi scroll");
                return false;
            }

            WebElement deleteButton = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//android.widget.Button[@content-desc='Xóa']")));
            deleteButton.click();
            log("✅ Đã click nút xóa");

            Thread.sleep(500);
            try {
                WebElement confirmButton = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//android.widget.Button[@content-desc='Xóa']")));
                confirmButton.click();
                log("✅ Đã click xác nhận xóa (theo resource-id)");
            } catch (Exception e) {
                log("⚠️ Không tìm thấy nút xác nhận theo resource-id, thử theo content-desc");

                try {
                    WebElement confirmButton = wait.until(ExpectedConditions.elementToBeClickable(
                            By.xpath("//android.view.ViewGroup[@resource-id='com.ss.android.ugc.trill:id/e1t']")));
                    confirmButton.click();
                    log("✅ Đã click xác nhận xóa (theo content-desc)");
                } catch (Exception e2) {
                    log("❌ Không thể tìm thấy nút xác nhận xóa");
                    return false;
                }
            }

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
                    Thread.sleep(1500);

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

            if (viewText.matches("\\d+")) {
                return Integer.parseInt(viewText);
            }

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
        deleteLowViewVideos(viewThreshold);
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
    }
}