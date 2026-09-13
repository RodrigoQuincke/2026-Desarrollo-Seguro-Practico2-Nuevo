package com.cinebuscador.controller;

import com.cinebuscador.model.Pelicula;
import com.cinebuscador.repository.PeliculaRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Controller
public class PeliculaController {

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 20_000_000L;

    private static final Set<String> ALLOWED_IMAGE_FORMATS =
        Set.of("png", "jpeg", "jpg");

    private static final Pattern SAFE_STORED_FILENAME =
        Pattern.compile("^[0-9a-fA-F-]{36}\\.png$");

    private final PeliculaRepository peliculaRepo;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    public PeliculaController(PeliculaRepository peliculaRepo) {
        this.peliculaRepo = peliculaRepo;
    }

    @GetMapping("/")
    public String index(
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false, defaultValue = "nombre") String ordenarPor,
            @RequestParam(required = false, defaultValue = "ASC") String sentido,
            Model model) {

        if (buscar != null && !buscar.isBlank()) {
            List<Object[]> resultadosRaw;

            if ("DESC".equalsIgnoreCase(sentido)) {
                resultadosRaw =
                    peliculaRepo.searchWithFuncionesDesc(buscar, ordenarPor);
            } else {
                resultadosRaw =
                    peliculaRepo.searchWithFunciones(buscar, ordenarPor);
            }

            List<Map<String, Object>> resultados =
                new java.util.ArrayList<>();

            for (Object[] row : resultadosRaw) {
                Map<String, Object> map = new HashMap<>();

                map.put("id", row[0]);
                map.put("nombre", row[1]);
                map.put("fechaHora", row[2]);
                map.put("disponibles", row[3]);
                map.put("descripcion", row[4]);
                map.put("afichePath", row[5]);

                resultados.add(map);
            }

            model.addAttribute("resultados", resultados);
        }

        model.addAttribute(
            "query",
            buscar != null ? buscar : ""
        );

        model.addAttribute("sort_by", ordenarPor);
        model.addAttribute("sort_dir", sentido);

        return "index";
    }

    @GetMapping("/upload/{id}")
    public String uploadForm(
            @PathVariable Integer id,
            Model model) {

        Pelicula pelicula = peliculaRepo.findById(id)
            .orElseThrow(
                () -> new EntityNotFoundException(
                    "Pelicula no encontrada"
                )
            );

        model.addAttribute("pelicula", pelicula);

        return "upload";
    }

    @PostMapping("/upload/{id}")
    public String uploadFile(
            @PathVariable Integer id,
            @RequestParam("afiche") MultipartFile archivo,
            RedirectAttributes redirectAttributes)
            throws IOException {

        Pelicula pelicula = peliculaRepo.findById(id)
            .orElseThrow(
                () -> new EntityNotFoundException(
                    "Pelicula no encontrada"
                )
            );

        if (archivo.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                "uploadError",
                "Selecciona una imagen no vacia."
            );

            return "redirect:/upload/" + id;
        }

        if (archivo.getSize() > MAX_UPLOAD_BYTES) {
            redirectAttributes.addFlashAttribute(
                "uploadError",
                "La imagen supera el limite de 5 MB."
            );

            return "redirect:/upload/" + id;
        }

        BufferedImage image;

        try (
            InputStream input = archivo.getInputStream();
            ImageInputStream imageInput =
                ImageIO.createImageInputStream(input)
        ) {

            if (imageInput == null) {
                redirectAttributes.addFlashAttribute(
                    "uploadError",
                    "El archivo no es una imagen valida."
                );

                return "redirect:/upload/" + id;
            }

            Iterator<ImageReader> readers =
                ImageIO.getImageReaders(imageInput);

            if (!readers.hasNext()) {
                redirectAttributes.addFlashAttribute(
                    "uploadError",
                    "El archivo no es una imagen valida."
                );

                return "redirect:/upload/" + id;
            }

            ImageReader reader = readers.next();

            try {
                reader.setInput(imageInput, true, true);

                String format =
                    reader.getFormatName()
                        .toLowerCase(Locale.ROOT);

                if (!ALLOWED_IMAGE_FORMATS.contains(format)) {
                    redirectAttributes.addFlashAttribute(
                        "uploadError",
                        "Solo se permiten imagenes PNG o JPEG."
                    );

                    return "redirect:/upload/" + id;
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (
                    width <= 0 ||
                    height <= 0 ||
                    (long) width * height > MAX_IMAGE_PIXELS
                ) {
                    redirectAttributes.addFlashAttribute(
                        "uploadError",
                        "La imagen tiene dimensiones no permitidas."
                    );

                    return "redirect:/upload/" + id;
                }

                image = reader.read(0);

            } finally {
                reader.dispose();
            }
        }

        Path basePath =
            Paths.get(uploadDir)
                .toAbsolutePath()
                .normalize();

        Files.createDirectories(basePath);

        String storedFilename =
            UUID.randomUUID() + ".png";

        Path target =
            basePath.resolve(storedFilename).normalize();

        if (!target.startsWith(basePath)) {
            throw new IOException(
                "Ruta de destino invalida"
            );
        }

        try (
            OutputStream output =
                Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE_NEW
                )
        ) {

            if (!ImageIO.write(image, "png", output)) {
                throw new IOException(
                    "No fue posible guardar la imagen"
                );
            }
        }

        String previousFilename =
            pelicula.getAfichePath();

        pelicula.setAfichePath(storedFilename);
        peliculaRepo.save(pelicula);

        if (
            previousFilename != null &&
            SAFE_STORED_FILENAME
                .matcher(previousFilename)
                .matches()
        ) {

            Files.deleteIfExists(
                basePath
                    .resolve(previousFilename)
                    .normalize()
            );
        }

        return "redirect:/";
    }

    @GetMapping("/uploads/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveFile(
            @PathVariable String filename)
            throws IOException {

        if (
            !SAFE_STORED_FILENAME
                .matcher(filename)
                .matches()
        ) {
            return ResponseEntity
                .notFound()
                .build();
        }

        Path basePath =
            Paths.get(uploadDir)
                .toAbsolutePath()
                .normalize();

        Path filePath =
            basePath
                .resolve(filename)
                .normalize();

        if (!filePath.startsWith(basePath)) {
            return ResponseEntity
                .notFound()
                .build();
        }

        Resource resource =
            new UrlResource(filePath.toUri());

        if (
            !resource.exists() ||
            !resource.isReadable()
        ) {
            return ResponseEntity
                .notFound()
                .build();
        }

        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + filename + "\""
            )
            .header(
                "X-Content-Type-Options",
                "nosniff"
            )
            .body(resource);
    }
}