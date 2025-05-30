package com.tiktok.ui;

import com.tiktok.util.DeviceConnector;
import com.tiktok.core.VideoDeleter;
import javax.swing.*;
import java.awt.*;
import java.util.List;

public class DeleteLowViewForm extends JFrame {
    private JTextField viewThresholdField;
    private JTextArea logArea;
    private JButton startButton;
    private JButton backButton;
    private JComboBox<String> deviceComboBox;
    private boolean isRunning = false;

    public DeleteLowViewForm() {
        setTitle("Xóa Video View Thấp");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(500, 600);
        setLocationRelativeTo(null);
        initComponents();
    }

    private void initComponents() {
        // Panel chính
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Panel nhập số view và chọn thiết bị
        JPanel inputPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        JLabel viewLabel = new JLabel("Ngưỡng view tối thiểu:");
        viewThresholdField = new JTextField(10);
        JLabel deviceLabel = new JLabel("Chọn thiết bị:");
        deviceComboBox = new JComboBox<>();
        
        inputPanel.add(viewLabel);
        inputPanel.add(viewThresholdField);
        inputPanel.add(deviceLabel);
        inputPanel.add(deviceComboBox);

        // Text area để hiển thị log
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);

        // Panel chứa các nút
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        startButton = new JButton("Bắt đầu");
        backButton = new JButton("Quay lại");
        
        startButton.addActionListener(e -> startDeleting());
        backButton.addActionListener(e -> {
            new MainMenu().setVisible(true);
            this.dispose();
        });

        buttonPanel.add(startButton);
        buttonPanel.add(backButton);

        // Thêm các component vào panel chính
        mainPanel.add(inputPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel);
        updateDeviceList();
    }

    private void updateDeviceList() {
        deviceComboBox.removeAllItems();
        List<String> devices = DeviceConnector.getAllDevices();
        if (devices.isEmpty()) {
            deviceComboBox.addItem("Không tìm thấy thiết bị");
        } else {
            for (String device : devices) {
                deviceComboBox.addItem(device);
            }
        }
    }

    private void startDeleting() {
        if (isRunning) {
            isRunning = false;
            startButton.setText("Bắt đầu");
            return;
        }

        String viewThresholdStr = viewThresholdField.getText().trim();
        
        if (viewThresholdStr.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập ngưỡng view!");
            return;
        }

        String selectedDevice = (String) deviceComboBox.getSelectedItem();
        if (selectedDevice == null || selectedDevice.equals("Không tìm thấy thiết bị")) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn thiết bị!");
            return;
        }

        try {
            int viewThreshold = Integer.parseInt(viewThresholdStr);
            
            if (viewThreshold < 0) {
                JOptionPane.showMessageDialog(this, "Ngưỡng view phải lớn hơn hoặc bằng 0!");
                return;
            }

            isRunning = true;
            startButton.setText("Dừng");
            logArea.setText("");

            // Chạy trong thread riêng để không block UI
            new Thread(() -> {
                try {
                    deleteLowViewVideos(selectedDevice, viewThreshold);
                } catch (Exception e) {
                    log("Lỗi: " + e.getMessage());
                } finally {
                    isRunning = false;
                    SwingUtilities.invokeLater(() -> startButton.setText("Bắt đầu"));
                }
            }).start();

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập số hợp lệ!");
        }
    }

    private void deleteLowViewVideos(String deviceId, int viewThreshold) {
        DeviceConnector connector = new DeviceConnector(deviceId);
        connector.setLogCallback(this::log);
        
        try {
            log("Đang kết nối với thiết bị: " + deviceId);
            connector.connect();

            if (!connector.isConnected()) {
                log("Không thể kết nối với thiết bị " + deviceId);
                return;
            }

            // Sử dụng VideoDeleter để xóa video
            VideoDeleter deleter = new VideoDeleter(connector.getDriver(), this::log);
            deleter.deleteLowViewVideosOptimized(viewThreshold);
            
            log("Hoàn thành xử lý thiết bị " + deviceId);

        } catch (Exception e) {
            log("Lỗi khi xử lý thiết bị " + deviceId + ": " + e.getMessage());
        } finally {
            connector.close();
        }
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
} 