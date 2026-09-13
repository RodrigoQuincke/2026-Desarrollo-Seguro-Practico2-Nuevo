package com.cinebuscador.repository;

import com.cinebuscador.model.Funcion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FuncionRepository
        extends JpaRepository<Funcion, Integer> {

    List<Funcion>
        findByNombreFuncionContainingIgnoreCase(
            String nombreFuncion
        );
}