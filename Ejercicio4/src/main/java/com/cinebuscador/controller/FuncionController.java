package com.cinebuscador.controller;

import com.cinebuscador.model.Funcion;
import com.cinebuscador.repository.FuncionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class FuncionController {

    private final FuncionRepository funcionRepo;

    public FuncionController(
            FuncionRepository funcionRepo) {

        this.funcionRepo = funcionRepo;
    }

    @GetMapping("/")
    public String search(
            @RequestParam(required = false) String buscar,
            Model model) {

        String query =
            buscar != null
                ? buscar.trim()
                : "";

        model.addAttribute("query", query);

        if (query.isBlank()) {
            List<Funcion> todas =
                funcionRepo.findAll();

            model.addAttribute(
                "resultados",
                todas
            );

            model.addAttribute(
                "mensaje",
                "Mostrando todas las funciones."
            );

            return "index";
        }

        List<Funcion> resultados =
            funcionRepo
                .findByNombreFuncionContainingIgnoreCase(
                    query
                );

        model.addAttribute(
            "resultados",
            resultados
        );

        if (resultados.isEmpty()) {
            model.addAttribute(
                "mensaje",
                "No se encontraron coincidencias."
            );
        } else {
            model.addAttribute(
                "mensaje",
                "Resultados buscando por: " + query
            );
        }

        return "index";
    }
}