package com.akine.organization.domain.port;

import com.akine.organization.domain.SolicitudDePrueba;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/** Pedidos de prueba gratis desde la landing (V28). */
public interface SolicitudDePruebaRepositoryPort {

	SolicitudDePrueba saveAndFlush(SolicitudDePrueba solicitud);

	Optional<SolicitudDePrueba> findById(Long id);

	Optional<SolicitudDePrueba> findByIdempotencyKey(String idempotencyKey);

	Page<SolicitudDePrueba> buscar(SolicitudDePrueba.Estado estado, String q, Pageable pageable);

	long countByEstado(SolicitudDePrueba.Estado estado);
}
