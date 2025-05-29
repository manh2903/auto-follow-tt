package com.tiktok.ui;

import com.tiktok.core.TikTokFollower;
import com.tiktok.util.DeviceConnector;
import com.tiktok.util.LogCallback;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FollowForm extends JFrame {
    private JTextField filePathField;
    private JTextField delayField;
    private JButton startButton;
    private JButton backButton;
    private List<TikTokFollower> followers;
    private ExecutorService executorService;
    private volatile boolean isRunning = false;
    private Map<String, JTextArea> deviceLogs;
    private JPanel logPanel;

    public FollowForm() {
        setTitle("TikTok Automation Tool v1.0 - Follow người dùng");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 800);
        setLocationRelativeTo(null);
        deviceLogs = new ConcurrentHashMap<>();
        initMenuBar();
        initComponents();
    }

    private void initMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // Menu File
        JMenu fileMenu = new JMenu("File");
        JMenuItem openMenuItem = new JMenuItem("Mở file danh sách");
        openMenuItem.addActionListener(e -> openFile());
        JMenuItem backMenuItem = new JMenuItem("Quay lại menu chính");
        backMenuItem.addActionListener(e -> backToMainMenu());
        JMenuItem exitMenuItem = new JMenuItem("Thoát");
        exitMenuItem.addActionListener(e -> System.exit(0));
        fileMenu.add(openMenuItem);
        fileMenu.add(backMenuItem);
        fileMenu.addSeparator();
        fileMenu.add(exitMenuItem);

        // Menu Tools
        JMenu toolsMenu = new JMenu("Công cụ");
        JMenuItem followMenuItem = new JMenuItem("Follow người dùng");
        followMenuItem.addActionListener(e -> startFollowing());
        toolsMenu.add(followMenuItem);

        // Menu Help
        JMenu helpMenu = new JMenu("Trợ giúp");
        JMenuItem aboutMenuItem = new JMenuItem("Giới thiệu");
        aboutMenuItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutMenuItem);

        menuBar.add(fileMenu);
        menuBar.add(toolsMenu);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);
    }

    private void openFile() {
        JFileChooser fileChooser = new JFileChooser();
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            filePathField.setText(fileChooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "TikTok Automation Tool v1.0\n\n" +
            "Công cụ tự động hóa cho TikTok\n" +
            "Hỗ trợ follow người dùng trên nhiều thiết bị",
            "Giới thiệu",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private void initComponents() {
        // Panel chính
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Panel điều khiển
        JPanel controlPanel = new JPanel(new GridLayout(4, 2, 5, 5));
        
        controlPanel.add(new JLabel("Đường dẫn file:"));
        filePathField = new JTextField();
        controlPanel.add(filePathField);

        controlPanel.add(new JLabel("Thời gian delay (ms):"));
        delayField = new JTextField("2000");
        controlPanel.add(delayField);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        startButton = new JButton("Bắt đầu");
        backButton = new JButton("Quay lại menu chính");
        
        startButton.addActionListener(e -> startFollowing());
        backButton.addActionListener(e -> backToMainMenu());
        
        buttonPanel.add(startButton);
        buttonPanel.add(backButton);
        controlPanel.add(buttonPanel);

        // Panel chứa các log area
        logPanel = new JPanel();
        logPanel.setLayout(new BoxLayout(logPanel, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JScrollPane(logPanel);
        scrollPane.setPreferredSize(new Dimension(900, 600));

        mainPanel.add(controlPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        add(mainPanel);

        // Xử lý khi đóng cửa sổ
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int choice = JOptionPane.showConfirmDialog(FollowForm.this,
                    "Bạn có chắc muốn thoát chương trình?\nCác tiến trình đang chạy sẽ bị dừng.",
                    "Xác nhận",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

                if (choice == JOptionPane.YES_OPTION) {
                    stopAllFollowers();
                    System.exit(0);
                }
            }
        });
    }

    private void backToMainMenu() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Bạn có chắc muốn quay lại menu chính?\nCác tiến trình đang chạy sẽ bị dừng.",
            "Xác nhận",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            stopAllFollowers();
            new MainMenu().setVisible(true);
            this.dispose();
        }
    }

    private void log(String deviceId, String message) {
        SwingUtilities.invokeLater(() -> {
            JTextArea logArea = deviceLogs.get(deviceId);
            if (logArea != null) {
                logArea.append(message + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    private void startFollowing() {
        String filePath = filePathField.getText().trim();
        if (filePath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập đường dẫn file!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        long delayTime;
        try {
            delayTime = Long.parseLong(delayField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Thời gian delay không hợp lệ. Sử dụng giá trị mặc định: 2000ms", 
                "Cảnh báo", JOptionPane.WARNING_MESSAGE);
            delayTime = 2000;
        }

        List<String> devices = DeviceConnector.getAllDevices();
        if (devices.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Không tìm thấy thiết bị nào!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Xóa các log area cũ
        deviceLogs.clear();
        logPanel.removeAll();
        logPanel.revalidate();
        logPanel.repaint();

        // Tạo log area mới cho mỗi thiết bị
        for (String deviceId : devices) {
            JPanel devicePanel = new JPanel(new BorderLayout());
            JLabel deviceLabel = new JLabel("Thiết bị: " + deviceId);
            deviceLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            
            JTextArea logArea = new JTextArea();
            logArea.setEditable(false);
            logArea.setRows(10);
            JScrollPane deviceScrollPane = new JScrollPane(logArea);
            deviceScrollPane.setPreferredSize(new Dimension(900, 150));
            
            devicePanel.add(deviceLabel, BorderLayout.NORTH);
            devicePanel.add(deviceScrollPane, BorderLayout.CENTER);
            devicePanel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            
            logPanel.add(devicePanel);
            deviceLogs.put(deviceId, logArea);
        }

        logPanel.revalidate();
        logPanel.repaint();

        startButton.setEnabled(false);

        executorService = Executors.newFixedThreadPool(devices.size());
        followers = new java.util.ArrayList<>();

        for (String deviceId : devices) {
            final String finalDeviceId = deviceId;
            TikTokFollower follower = new TikTokFollower(filePath, deviceId, delayTime, message -> log(finalDeviceId, message));
            followers.add(follower);
            
            executorService.submit(() -> {
                try {
                    follower.connectToDevice();
                    follower.followUsers();
                } catch (Exception e) {
                    log(finalDeviceId, "Lỗi khi follow với thiết bị " + deviceId + ": " + e.getMessage());
                }
            });
        }

        executorService.shutdown();
        new Thread(() -> {
            try {
                if (!executorService.awaitTermination(30, TimeUnit.MINUTES)) {
                    for (String deviceId : devices) {
                        log(deviceId, "Chương trình đã chạy quá thời gian cho phép!");
                    }
                    executorService.shutdownNow();
                }
                SwingUtilities.invokeLater(() -> {
                    startButton.setEnabled(true);
                });
            } catch (InterruptedException e) {
                for (String deviceId : devices) {
                    log(deviceId, "Chương trình bị gián đoạn!");
                }
                executorService.shutdownNow();
            }
        }).start();
    }

    private void stopAllFollowers() {
        isRunning = false;
        if (executorService != null) {
            executorService.shutdownNow();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        if (followers != null) {
            for (TikTokFollower follower : followers) {
                follower.close();
            }
            followers.clear();
        }
        startButton.setEnabled(true);
        
        // Log cho tất cả các thiết bị
        for (String deviceId : deviceLogs.keySet()) {
            log(deviceId, "Đã dừng tất cả các tiến trình!");
        }
        
        // Reset trạng thái
        isRunning = true;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new FollowForm().setVisible(true);
        });
    }
} 