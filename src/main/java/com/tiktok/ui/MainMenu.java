package com.tiktok.ui;

import javax.swing.*;
import java.awt.*;

public class MainMenu extends JFrame {
    public MainMenu() {
        setTitle("TikTok Automation Tool v1.0");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(400, 500);
        setLocationRelativeTo(null);
        initComponents();
    }

    private void initComponents() {
        // Panel chính
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Logo và tiêu đề
        JPanel headerPanel = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel("TIKTOK AUTOMATION", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        headerPanel.add(titleLabel, BorderLayout.CENTER);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // Panel chứa các nút chức năng
        JPanel buttonPanel = new JPanel(new GridLayout(4, 1, 10, 10));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(20, 0, 20, 0));

        // Tạo các nút chức năng
        JButton followButton = createMenuButton("Follow người dùng", "👥");
        JButton likeButton = createMenuButton("Like video", "❤️");
        JButton commentButton = createMenuButton("Comment video", "💬");
        JButton exitButton = createMenuButton("Thoát", "🚪");

        // Thêm sự kiện cho các nút
        followButton.addActionListener(e -> {
            new FollowForm().setVisible(true);
            this.dispose();
        });

        likeButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(this, "Tính năng đang được phát triển!", 
                "Thông báo", JOptionPane.INFORMATION_MESSAGE);
        });

        commentButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(this, "Tính năng đang được phát triển!", 
                "Thông báo", JOptionPane.INFORMATION_MESSAGE);
        });

        exitButton.addActionListener(e -> System.exit(0));

        // Thêm các nút vào panel
        buttonPanel.add(followButton);
        buttonPanel.add(likeButton);
        buttonPanel.add(commentButton);
        buttonPanel.add(exitButton);

        mainPanel.add(buttonPanel, BorderLayout.CENTER);

        // Footer
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JLabel versionLabel = new JLabel("Version 1.0");
        footerPanel.add(versionLabel);
        mainPanel.add(footerPanel, BorderLayout.SOUTH);

        add(mainPanel);
    }

    private JButton createMenuButton(String text, String icon) {
        JButton button = new JButton(icon + "  " + text);
        button.setFont(new Font("Arial", Font.PLAIN, 16));
        button.setFocusPainted(false);
        button.setBackground(new Color(0, 122, 255));
        button.setForeground(Color.WHITE);
        button.setBorderPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return button;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new MainMenu().setVisible(true);
        });
    }
} 