package com.tiktok;

import java.util.Scanner;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) {
        String filePath;
        long delayTime = 2000; // Giá trị mặc định 2 giây
        
        if (args.length > 0) {
            filePath = args[0];
            System.out.println("Sử dụng file: " + filePath);
            if (args.length > 1) {
                try {
                    delayTime = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    System.err.println("Thời gian delay không hợp lệ, sử dụng giá trị mặc định: 2000ms");
                }
            }
        } else {
            Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
            System.out.println("Nhập đường dẫn đến file chứa danh sách ID TikTok:");
            filePath = scanner.nextLine();
            
            System.out.println("Nhập thời gian delay giữa các lần follow (ms) [mặc định: 2000]:");
            String delayInput = scanner.nextLine();
            if (!delayInput.trim().isEmpty()) {
                try {
                    delayTime = Long.parseLong(delayInput);
                } catch (NumberFormatException e) {
                    System.err.println("Thời gian delay không hợp lệ, sử dụng giá trị mặc định: 2000ms");
                }
            }
            scanner.close();
        }

        // Tạo instance của MultiDeviceTikTokFollower
        MultiDeviceTikTokFollower multiFollower = new MultiDeviceTikTokFollower(filePath, delayTime);
        
        // Thêm shutdown hook để xử lý khi người dùng nhấn Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nĐang thoát chương trình...");
            multiFollower.stop();
            System.out.println("Đã thoát chương trình.");
        }));

        try {
            System.out.println("Bắt đầu follow người dùng trên tất cả các thiết bị...");
            multiFollower.startFollowing();
        } catch (Exception e) {
            System.err.println("Lỗi khi thực hiện follow: " + e.getMessage());
            multiFollower.stop();
        }
    }
} 