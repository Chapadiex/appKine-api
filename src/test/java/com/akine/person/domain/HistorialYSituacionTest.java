package com.akine.person.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AKINE B-4: invariantes del evento de autorizacion y la situacion derivada de la orden. */
@DisplayName("Historial de autorizacion y situacion de la orden (B-4)")
class HistorialYSituacionTest {

	private static final LocalDate ENERO = LocalDate.of(2027, 1, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2027, 12, 31);
	private static final LocalDate MARZO = LocalDate.of(2027, 3, 1);

	@Test
	@DisplayName("solo el alta va sin estado anterior, y los hechos del ledger llevan movimiento")
	void invariantes_del_evento() {
		Autorizacion autorizacion = autorizacion(EstadoAutorizacion.PENDIENTE, 51L, 0);

		assertThatThrownBy(() -> AutorizacionEvento.de(autorizacion, TipoEventoAutorizacion.ALTA,
				EstadoAutorizacion.PENDIENTE, null, null, null, 1L, Instant.now()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AutorizacionEvento.de(autorizacion,
				TipoEventoAutorizacion.APROBACION, null, null, null, null, 1L, Instant.now()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AutorizacionEvento.de(autorizacion,
				TipoEventoAutorizacion.CONSUMO, EstadoAutorizacion.APROBADA, null, null, null, 1L,
				Instant.now()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AutorizacionEvento.delLedger(autorizacion,
				TipoEventoAutorizacion.ANULACION, 9L, 1, null, null, null, 1L, Instant.now()))
				.isInstanceOf(IllegalArgumentException.class);

		AutorizacionEvento consumo = AutorizacionEvento.delLedger(autorizacion,
				TipoEventoAutorizacion.CONSUMO, 9L, 1, "Sesion 3", "  ", null, null, null);
		assertThat(consumo.getEstadoAnterior()).isEqualTo(consumo.getEstadoNuevo());
		assertThat(consumo.getConsultorioId()).as("sin sede declarada, la de la autorizacion")
				.isEqualTo(20L);
		assertThat(consumo.getMotivo()).isNull();
		assertThat(consumo.getOcurridoEn()).isNotNull();
	}

	@Test
	@DisplayName("el motivo largo se recorta al tope de la columna")
	void motivo_recortado() {
		AutorizacionEvento evento = AutorizacionEvento.de(
				autorizacion(EstadoAutorizacion.PENDIENTE, null, 0),
				TipoEventoAutorizacion.OBSERVACION, EstadoAutorizacion.PENDIENTE, null,
				"x".repeat(1500), 20L, 1L, Instant.now());

		assertThat(evento.getMotivo()).hasSize(AutorizacionEvento.MOTIVO_MAXIMO);
	}

	@Test
	@DisplayName("la situacion de la orden sigue la precedencia declarada")
	void situacion() {
		OrdenMedica orden = orden(10, DICIEMBRE);

		assertThat(SituacionOrdenMedica.de(orden, List.of(), MARZO))
				.isEqualTo(SituacionOrdenMedica.SIN_AUTORIZACION);
		assertThat(SituacionOrdenMedica.de(orden,
				List.of(autorizacion(EstadoAutorizacion.PENDIENTE, 51L, 0)), MARZO))
				.isEqualTo(SituacionOrdenMedica.EN_TRAMITE);
		assertThat(SituacionOrdenMedica.de(orden,
				List.of(autorizacion(EstadoAutorizacion.RECHAZADA, 51L, 0)), MARZO))
				.isEqualTo(SituacionOrdenMedica.RECHAZADA);
		assertThat(SituacionOrdenMedica.de(orden, List.of(
				autorizacion(EstadoAutorizacion.RECHAZADA, 51L, 0),
				autorizacion(EstadoAutorizacion.APROBADA, 51L, 0)), MARZO))
				.isEqualTo(SituacionOrdenMedica.AUTORIZADA);
		assertThat(SituacionOrdenMedica.de(orden,
				List.of(autorizacion(EstadoAutorizacion.APROBADA, 51L, 4)), MARZO))
				.isEqualTo(SituacionOrdenMedica.EN_CURSO);
		assertThat(SituacionOrdenMedica.de(orden,
				List.of(autorizacion(EstadoAutorizacion.APROBADA, 51L, 4)), DICIEMBRE.plusDays(1)))
				.isEqualTo(SituacionOrdenMedica.VENCIDA);
		assertThat(SituacionOrdenMedica.de(orden,
				List.of(autorizacion(EstadoAutorizacion.APROBADA, 51L, 10)), DICIEMBRE.plusDays(1)))
				.as("cumplida manda sobre vencida")
				.isEqualTo(SituacionOrdenMedica.CUMPLIDA);

		orden.deactivate(Instant.now(), "cargada por error");
		assertThat(SituacionOrdenMedica.de(orden, List.of(), MARZO))
				.isEqualTo(SituacionOrdenMedica.ANULADA);
	}

	@Test
	@DisplayName("no cuentan las autorizaciones de otra orden, sin orden ni dadas de baja")
	void solo_las_propias() {
		OrdenMedica orden = orden(null, null);
		Autorizacion deBaja = autorizacion(EstadoAutorizacion.APROBADA, 51L, 2);
		deBaja.deactivate(Instant.now(), "error");

		List<Autorizacion> ajenas = List.of(
				autorizacion(EstadoAutorizacion.APROBADA, 52L, 3),
				autorizacion(EstadoAutorizacion.APROBADA, null, 3),
				deBaja);

		assertThat(SituacionOrdenMedica.de(orden, ajenas, MARZO))
				.isEqualTo(SituacionOrdenMedica.SIN_AUTORIZACION);
		assertThat(SituacionOrdenMedica.sesionesConsumidas(orden, ajenas)).isZero();
	}

	private static OrdenMedica orden(Integer sesiones, LocalDate hasta) {
		OrdenMedica orden = new OrdenMedica(7L, 1204L, 20L, null, "OM-1", "Dra. Sintetica",
				"MP 1", ENERO, null, sesiones, ENERO, hasta, null);
		ReflectionTestUtils.setField(orden, "id", 51L);
		return orden;
	}

	private static Autorizacion autorizacion(
			EstadoAutorizacion estado, Long ordenId, int consumidas) {
		Autorizacion autorizacion = new Autorizacion(7L, 1204L, 20L, 412L, ordenId, 33L, "AUT-1",
				EstadoAutorizacion.PENDIENTE, 10, ENERO, DICIEMBRE, null, null);
		ReflectionTestUtils.setField(autorizacion, "estado", estado);
		ReflectionTestUtils.setField(autorizacion, "id", 77L);
		ReflectionTestUtils.setField(autorizacion, "cantidadConsumida", consumidas);
		return autorizacion;
	}
}
