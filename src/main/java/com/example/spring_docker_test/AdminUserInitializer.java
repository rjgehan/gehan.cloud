package com.example.spring_docker_test;

import java.security.SecureRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminUserInitializer implements CommandLineRunner {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String accessUsername;
    private final String accessPin;
    private final String resetPin;

    public AdminUserInitializer(
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${app.security.username}") String accessUsername,
            @Value("${app.security.pin}") String accessPin,
            @Value("${app.security.reset-pin}") String resetPin) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessUsername = accessUsername;
        this.accessPin = accessPin;
        this.resetPin = resetPin;
    }

    @Override
    public void run(String... args) {
        validateAccessUsername();

        UserAccount account = users.findByUsernameIgnoreCase(accessUsername).orElse(null);
        if (resetPin != null && !resetPin.isBlank()) {
            validatePin(resetPin, "APP_RESET_PIN");
            if (account == null) {
                users.save(new UserAccount(accessUsername, passwordEncoder.encode(resetPin), "USER"));
            } else {
                account.setPasswordHash(passwordEncoder.encode(resetPin));
                users.save(account);
            }
            System.out.println("Updated family app PIN from APP_RESET_PIN.");
            return;
        }

        if (account == null) {
            String bootstrapPin = accessPin;
            if (bootstrapPin == null || bootstrapPin.isBlank()) {
                bootstrapPin = generateBootstrapPin();
                System.out.println();
                System.out.println("============================================================");
                System.out.println("Generated bootstrap family app PIN");
                System.out.println("PIN: " + bootstrapPin);
                System.out.println("Set APP_PIN to choose your own first PIN.");
                System.out.println("============================================================");
                System.out.println();
            } else {
                validatePin(bootstrapPin, "APP_PIN");
            }

            users.save(new UserAccount(accessUsername, passwordEncoder.encode(bootstrapPin), "USER"));
        }
    }

    private void validateAccessUsername() {
        if (accessUsername == null || accessUsername.isBlank()) {
            throw new IllegalStateException("APP_USERNAME must not be blank.");
        }
    }

    private static void validatePin(String pin, String sourceName) {
        if (!pin.matches("\\d{8}")) {
            throw new IllegalStateException(sourceName + " must be exactly 8 digits.");
        }
    }

    private static String generateBootstrapPin() {
        SecureRandom random = new SecureRandom();
        int pin = random.nextInt(100_000_000);
        return String.format("%08d", pin);
    }
}
