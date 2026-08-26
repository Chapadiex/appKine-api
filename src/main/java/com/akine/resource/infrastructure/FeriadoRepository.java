package com.akine.resource.infrastructure;

import com.akine.resource.domain.Feriado;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Acceso al calendario de feriados. <b>Sin {@code organizationId}, y no es un olvido:</b> la
 * tabla {@code feriado} (V22) es global por ADR-0022. Si alguien le agrega el parametro —a esta
 * firma, al {@code WHERE} o al {@code spi}— es que entendio mal el alcance: la decision que SI
 * es de cada tenant (si la sede cierra ese dia) vive en {@code consultorio_calendario}, no aca.
 */
public interface FeriadoRepository extends JpaRepository<Feriado, Long>, FeriadoRepositoryPort {

	/**
	 * Feriados de un pais en {@code [desde, hasta]}. Derivada: {@code Between} en JPA es
	 * inclusiva en los dos extremos, lo que corresponde aca porque {@code fecha} es un dia
	 * calendario puntual y no una ventana con limite exclusivo como las demas tablas de esta
	 * etapa.
	 */
	@Override
	List<Feriado> findByPaisAndFechaBetween(String pais, LocalDate desde, LocalDate hasta);
}
