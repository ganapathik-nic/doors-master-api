package org.gepnic.doors.masterapi.service;

import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Random;

@Service
public class CaptchaService {

    private final Random random = new Random();
    private final String SOURCES = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // O and 0 removed for clarity

    public String generateText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(SOURCES.charAt(random.nextInt(SOURCES.length())));
        }
        return sb.toString();
    }

    public String generateBase64Image(String text) {
        int width = 120;
        int height = 40;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // Background
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        // Add Noise (random lines)
        g.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i < 10; i++) {
            g.drawLine(random.nextInt(width), random.nextInt(height), 
                       random.nextInt(width), random.nextInt(height));
        }

        // Add Text
        g.setFont(new Font("Arial", Font.BOLD, 24));
        for (int i = 0; i < text.length(); i++) {
            g.setColor(new Color(random.nextInt(100), random.nextInt(100), random.nextInt(100)));
            g.drawString(String.valueOf(text.charAt(i)), 15 + (i * 15), 28);
        }

        g.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Captcha Image");
        }
    }
}