package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.RealizadoEnElCasoProbe;
import com.akine.clinical.spi.RealizadoPorOferta;
import com.akine.encounter.domain.ConteoDeSesionesPorOferta;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Contesta {@link RealizadoEnElCasoProbe} desde el modulo que es dueño de la Sesion.
 *
 * <p>La interfaz vive en {@code clinical.spi} y la implementacion aca porque la dependencia entre
 * los dos modulos ya va en este sentido: {@code encounter} conoce a {@code clinical} desde 06.01
 * ({@code HistoriaClinicaDirectory}) y 04.03 ({@code CasoDirectory}), nunca al reves. Es el mismo
 * patron —y el mismo argumento— que {@link EncounterAtencionProbe}, que implementa una sonda
 * declarada en {@code scheduling.spi}.
 *
 * <p><b>Traduccion de forma y nada mas.</b> Que significa el numero lo decide M11: si el plan esta
 * completo, si avisa, si el profesional decide finalizarlo. Este modulo no conoce al Plan de
 * Tratamiento y no tiene por que — y justamente por eso el avance se puede derivar sin que las dos
 * mitades tengan que mantenerse de acuerdo.
 *
 * <p>{@code readOnly = true} y {@code REQUIRED}: se une a la transaccion de lectura del llamador si
 * hay una. No abre escritura porque no escribe nada, que es la otra mitad de la promesa de este
 * {@code spi}.
 */
@Component
public class EncounterRealizadoEnElCasoProbe implements RealizadoEnElCasoProbe {

	private final SesionRepositoryPort sesiones;

	public EncounterRealizadoEnElCasoProbe(SesionRepositoryPort sesiones) {
		this.sesiones = sesiones;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>El {@code SUM} de JPQL devuelve {@code Long} y el {@code spi} declara {@code int}: la
	 * conversion es segura porque una oferta no acumula dos mil millones de sesiones en un caso, y
	 * declarar {@code long} afuera obligaria a todo consumidor a manejar un ancho que ningun dato
	 * real usa.
	 *
	 * <p>Un {@code null} en alguno de los dos conteos no lo puede producir esta consulta —el
	 * {@code SUM} de un {@code CASE} con {@code ELSE 0L} nunca es nulo sobre un grupo que existe—
	 * pero se normaliza igual: un {@code NullPointerException} en el desempaquetado dejaria sin
	 * avance a una pantalla entera por una fila rara.
	 */
	@Override
	@Transactional(readOnly = true)
	public List<RealizadoPorOferta> contarPorOfertaEnElCaso(long organizationId, long casoId) {
		return sesiones.contarCerradasPorOferta(organizationId, casoId).stream()
				.map(EncounterRealizadoEnElCasoProbe::traducir)
				.toList();
	}

	private static RealizadoPorOferta traducir(ConteoDeSesionesPorOferta conteo) {
		return new RealizadoPorOferta(
				conteo.ofertaId(),
				conteo.realizadas() == null ? 0 : conteo.realizadas().intValue(),
				conteo.canceladas() == null ? 0 : conteo.canceladas().intValue());
	}
}
