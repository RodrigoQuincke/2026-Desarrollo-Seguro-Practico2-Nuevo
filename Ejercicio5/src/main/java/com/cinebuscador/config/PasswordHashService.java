package com.cinebuscador.config;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordHashService {

    private final BCryptPasswordEncoder passwordEncoder =
        new BCryptPasswordEncoder(12);

    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    public boolean matches(
            String rawPassword,
            String storedHash) {

        return passwordEncoder.matches(
            rawPassword,
            storedHash
        );
    }
}