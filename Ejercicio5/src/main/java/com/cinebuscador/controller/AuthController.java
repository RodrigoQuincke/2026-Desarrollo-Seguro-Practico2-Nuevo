package com.cinebuscador.controller;

import com.cinebuscador.config.PasswordHashService;
import com.cinebuscador.model.User;
import com.cinebuscador.repository.UserRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordHashService passwordHashService;

    public AuthController(
            UserRepository userRepository,
            PasswordHashService passwordHashService) {

        this.userRepository = userRepository;
        this.passwordHashService = passwordHashService;
    }

    @GetMapping("/")
    public String loginPage(Model model) {
        addForms(model);
        return "index";
    }

    @PostMapping("/login")
    public String login(
            @RequestParam String username,
            @RequestParam String password,
            Model model) {

        User user =
            userRepository
                .findByUsername(username)
                .orElse(null);

        if (
            user != null &&
            passwordHashService.matches(
                password,
                user.getPassword()
            )
        ) {

            model.addAttribute(
                "loginSuccess",
                true
            );

            model.addAttribute(
                "welcomeUser",
                username
            );

            addForms(model);

            return "index";
        }

        model.addAttribute(
            "loginError",
            "Usuario o contraseña incorrecta"
        );

        addForms(model);

        return "index";
    }

    @PostMapping("/register")
    public String register(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String confirmPwd,
            Model model) {

        if (!password.equals(confirmPwd)) {

            model.addAttribute(
                "registerError",
                "Las contraseñas no coinciden"
            );

            addForms(model);

            return "index";
        }

        if (
            userRepository
                .findByUsername(username)
                .isPresent()
        ) {

            model.addAttribute(
                "registerError",
                "El usuario ya existe"
            );

            addForms(model);

            return "index";
        }

        User nuevoUsuario = new User();

        nuevoUsuario.setUsername(username);

        nuevoUsuario.setPassword(
            passwordHashService.hash(password)
        );

        userRepository.save(nuevoUsuario);

        model.addAttribute(
            "registerSuccess",
            true
        );

        model.addAttribute(
            "registeredUsername",
            username
        );

        addForms(model);

        return "index";
    }

    public static class LoginForm {

        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class RegisterForm {

        private String username;
        private String password;
        private String confirmPwd;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getConfirmPwd() {
            return confirmPwd;
        }

        public void setConfirmPwd(
                String confirmPwd) {

            this.confirmPwd = confirmPwd;
        }
    }

    private void addForms(Model model) {

        model.addAttribute(
            "loginForm",
            new LoginForm()
        );

        model.addAttribute(
            "registerForm",
            new RegisterForm()
        );
    }
}