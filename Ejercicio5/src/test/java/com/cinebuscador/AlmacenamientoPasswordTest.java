package com.cinebuscador;

import com.cinebuscador.controller.AuthController;
import com.cinebuscador.model.User;
import com.cinebuscador.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias - Ejercicio 5: almacenamiento inseguro de contraseñas.
 *
 * Vulnerabilidad (rama main):
 *   AuthController guarda las contraseñas cifradas con EncryptionService (AES/ECB) usando una
 *   clave escrita en el código fuente. Es reversible: con la clave del código se recupera la
 *   contraseña original. Además ECB es determinístico (misma contraseña -> mismo valor guardado)
 *   y la aplicación muestra la contraseña cifrada en la pantalla.
 *
 * Mitigación (rama practico-2):
 *   PasswordHashService usa BCrypt (costo 12): hash de un solo sentido con salt aleatorio.
 *   No se puede revertir, la misma contraseña produce hashes distintos y ya no se muestra.
 *
 * Resultado esperado:
 *   main        -> FALLAN todos los tests.
 *   practico-2  -> PASAN todos.
 *
 * Son pruebas unitarias: no levantan Spring, ni base de datos, ni Docker. El repositorio de
 * usuarios se reemplaza por un "mock" de Mockito (un objeto falso que registra lo que se le pide
 * guardar), así el test puede ver exactamente qué valor se guardaría en la base.
 *
 * Ejecutar (desde la carpeta Ejercicio5): ver README.
 */
class AlmacenamientoPasswordTest {

    private static final String PASSWORD = "123456";

    /** Clave AES que estaba escrita en EncryptionService (rama main). AES-256 usa sus primeros 32 bytes. */
    private static final String CLAVE_DEL_CODIGO = "MySup3rS3cr3tK3y!2024CineBuscadorAES";

    private UserRepository repositorio;
    private AuthController controlador;

    @BeforeEach
    void preparar() throws Exception {
        repositorio = mock(UserRepository.class);
        // Por defecto el mock devuelve Optional.empty(): "el usuario no existe", así el registro sigue adelante.
        controlador = crearControlador(repositorio);
    }

    // ----------------------------------------------------------------------------------
    // Tests: deben FALLAR en main y PASAR en practico-2.
    // ----------------------------------------------------------------------------------

    /**
     * Lo guardado debe ser un hash BCrypt de costo 12 ($2a$12$ + 53 caracteres).
     * main:       guarda AES en Base64 (por ejemplo "tzNLDx9FAZr9/6bfDb++tg==") -> FALLA.
     * practico-2: guarda "$2a$12$..." -> PASA.
     */
    @Test
    void laPasswordSeGuardaComoHashBcrypt() {
        String guardado = registrarYObtenerValorGuardado("usuario1", PASSWORD);

        assertNotEquals(PASSWORD, guardado, "La contraseña se guardó en texto plano");
        assertTrue(guardado.matches("^\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}$"),
                "Lo guardado no es un hash BCrypt de costo 12: " + guardado);
    }

    /**
     * Reproduce la prueba de concepto del Práctico 2 (la de OpenSSL), pero en Java:
     * intenta descifrar lo guardado con la clave que estaba en el código fuente.
     * main:       el descifrado devuelve "123456" -> FALLA.
     * practico-2: un hash BCrypt no se puede descifrar -> PASA.
     */
    @Test
    void laPasswordNoSePuedeRecuperarConLaClaveDelCodigo() {
        String guardado = registrarYObtenerValorGuardado("usuario1", PASSWORD);

        String recuperada = intentarDescifrarAes(guardado);

        assertNotEquals(PASSWORD, recuperada,
                "Se recuperó la contraseña original descifrando con la clave del código fuente");
    }

    /**
     * Dos usuarios con la misma contraseña deben tener valores guardados distintos.
     * main:       AES/ECB sin IV es determinístico -> mismo valor -> FALLA.
     * practico-2: BCrypt usa un salt aleatorio por contraseña -> valores distintos -> PASA.
     */
    @Test
    void mismaPasswordGeneraValoresGuardadosDistintos() {
        controlador.register("prueba1", PASSWORD, PASSWORD, new ExtendedModelMap());
        controlador.register("prueba2", PASSWORD, PASSWORD, new ExtendedModelMap());

        List<User> guardados = usuariosGuardados();
        String guardado1 = guardados.get(guardados.size() - 2).getPassword();
        String guardado2 = guardados.get(guardados.size() - 1).getPassword();

        assertNotEquals(guardado1, guardado2,
                "La misma contraseña produjo el mismo valor guardado: " + guardado1);
    }

    /**
     * Al registrarse, la página no debe recibir la contraseña cifrada para mostrarla.
     * main:       register agrega "encryptedPassword" al modelo -> FALLA.
     * practico-2: ya no se agrega -> PASA.
     */
    @Test
    void elRegistroNoExponeLaPasswordAlmacenada() {
        Model modelo = new ExtendedModelMap();

        controlador.register("usuario1", PASSWORD, PASSWORD, modelo);

        assertFalse(modelo.containsAttribute("encryptedPassword"),
                "La pantalla de registro recibe la contraseña almacenada");
    }

    /**
     * Al iniciar sesión, la página no debe recibir la contraseña cifrada para mostrarla.
     * main:       login agrega "encryptedPassword" al modelo -> FALLA.
     * practico-2: ya no se agrega -> PASA.
     */
    @Test
    void elLoginNoExponeLaPasswordAlmacenada() {
        controlador.register("usuario1", PASSWORD, PASSWORD, new ExtendedModelMap());
        User guardado = usuariosGuardados().get(0);
        // A partir de ahora el repositorio "encuentra" al usuario recién registrado.
        when(repositorio.findByUsername("usuario1")).thenReturn(Optional.of(guardado));

        Model modelo = new ExtendedModelMap();
        controlador.login("usuario1", PASSWORD, modelo);

        assertFalse(modelo.containsAttribute("encryptedPassword"),
                "La pantalla de login recibe la contraseña almacenada");
    }

    // ----------------------------------------------------------------------------------
    // Funciones auxiliares
    // ----------------------------------------------------------------------------------

    /** Registra un usuario y devuelve la contraseña tal como se guardaría en la base. */
    private String registrarYObtenerValorGuardado(String usuario, String password) {
        controlador.register(usuario, password, password, new ExtendedModelMap());
        List<User> guardados = usuariosGuardados();
        return guardados.get(guardados.size() - 1).getPassword();
    }

    /** Devuelve todos los usuarios que el controlador le pidió guardar al repositorio (save). */
    private List<User> usuariosGuardados() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repositorio, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    /**
     * Descifra con AES/ECB y la clave del código, igual que EncryptionService de main.
     * Devuelve null si no se puede descifrar (por ejemplo, porque es un hash BCrypt).
     */
    private static String intentarDescifrarAes(String valorGuardado) {
        try {
            byte[] clave = Arrays.copyOf(CLAVE_DEL_CODIGO.getBytes(StandardCharsets.UTF_8), 32);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(clave, "AES"));
            byte[] descifrado = cipher.doFinal(Base64.getDecoder().decode(valorGuardado));
            return new String(descifrado, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Crea el AuthController sin Spring.
     *
     * El constructor es distinto en cada rama:
     *   main:       AuthController(UserRepository)
     *   practico-2: AuthController(UserRepository, PasswordHashService)
     * Como este mismo archivo tiene que compilar en las dos ramas, no se puede nombrar
     * PasswordHashService (no existe en main). Por eso se usa el constructor que tenga la clase:
     * al UserRepository se le pasa el mock, y cualquier otra dependencia se crea con su
     * constructor vacío (en practico-2, un PasswordHashService real con BCrypt).
     */
    private static AuthController crearControlador(UserRepository repositorio) throws Exception {
        Constructor<?> constructor = AuthController.class.getConstructors()[0];
        Class<?>[] tipos = constructor.getParameterTypes();
        Object[] argumentos = new Object[tipos.length];
        for (int i = 0; i < tipos.length; i++) {
            argumentos[i] = (tipos[i] == UserRepository.class)
                    ? repositorio
                    : tipos[i].getDeclaredConstructor().newInstance();
        }
        return (AuthController) constructor.newInstance(argumentos);
    }
}
