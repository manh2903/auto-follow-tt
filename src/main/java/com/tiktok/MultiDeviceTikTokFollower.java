package com.tiktok;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MultiDeviceTikTokFollower {
    private final String userIdsFilePath;
    private final List<String> devices;
    private final List<TikTokFollower> followers;
    private final ExecutorService executorService;
    private final long delayTime;

    public MultiDeviceTikTokFollower(String userIdsFilePath, long delayTime) {
        this.userIdsFilePath = userIdsFilePath;
        this.devices = TikTokFollower.getAllDevices();
        this.followers = new ArrayList<>();
        this.executorService = Executors.newFixedThreadPool(devices.size());
        this.delayTime = delayTime;
    }

    public void startFollowing() {
        if (devices.isEmpty()) {
            System.err.println("Không có thiết bị nào được kết nối!");
            return;
        }

        System.out.println("Bắt đầu follow với " + devices.size() + " thiết bị");
        System.out.println("Thời gian delay giữa các lần follow: " + delayTime + "ms");

        // Tạo và khởi động các follower cho mỗi thiết bị
        for (String deviceId : devices) {
            TikTokFollower follower = new TikTokFollower(userIdsFilePath, deviceId, delayTime);
            followers.add(follower);
            
            // Kết nối thiết bị và bắt đầu follow trong một thread riêng
            executorService.submit(() -> {
                try {
                    follower.connectToDevice();
                    follower.followUsers();
                } catch (Exception e) {
                    System.err.println("Lỗi khi follow với thiết bị " + deviceId + ": " + e.getMessage());
                }
            });
        }

        // Đợi tất cả các thread hoàn thành
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.MINUTES)) {
                System.err.println("Chương trình đã chạy quá thời gian cho phép!");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            System.err.println("Chương trình bị gián đoạn!");
            executorService.shutdownNow();
        }

        // Đóng tất cả các follower
        for (TikTokFollower follower : followers) {
            follower.close();
        }
    }

    public void stop() {
        executorService.shutdownNow();
        for (TikTokFollower follower : followers) {
            follower.close();
        }
    }
} 