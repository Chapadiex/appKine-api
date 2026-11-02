package com.akine.scheduling.application;

import com.akine.person.spi.PacienteSnapshot;
import com.akine.scheduling.domain.Recepcion;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.spi.AtencionProbe.AtencionDeTurno;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El estado operativo no se guarda: se deriva al leer de tres maquinas independientes, turno, recepcion
 * y sesion (DP-05, docs/diseno/AKINE-turnos-flujo-unico.md §2). Lo que importa es que hecho manda.
 */
class EstadoOperativoDeTurnoTest {

	private static final Instant AHORA = Instant.parse("2027-03-08T12:00:00Z");
	private static final AtencionDeTurno ABIERTA = new AtencionDeTurno(70L, false);
	private static final AtencionDeTurno CERRADA = new AtencionDeTurno(70L, true);

	@Test
	@DisplayName("sin recepcion ni atencion es el estado de la reserva")
	void el_de_la_reserva() {
		Turno turno = turno(AHORA.plus(Duration.ofDays(1)));
		assertThat(RecepcionService.estadoOperativo(turno, null, null)).isEqualTo("RESERVADO");

		turno.confirmar(AHORA);
		assertThat(RecepcionService.estadoOperativo(turno, null, null)).isEqualTo("CONFIRMADO");
	}

	@Test
	@DisplayName("cancelado manda sobre todo: la baja libero el lugar")
	void cancelado() {
		Turno turno = turno(AHORA.plus(Duration.ofDays(1)));
		Recepcion recepcion = Recepcion.llegada(turno, AHORA, 9L);
		turno.cancelar("No puede venir", 9L, AHORA);

		assertThat(RecepcionService.estadoOperativo(turno, recepcion, ABIERTA)).isEqualTo("CANCELADO");
	}

	@Test
	@DisplayName("una atencion prueba que vino, aunque el turno figure ausente o la recepcion se haya cerrado")
	void la_atencion_manda_sobre_la_recepcion() {
		Turno turno = turno(AHORA.minus(Duration.ofMinutes(5)));
		Recepcion enEspera = Recepcion.llegada(turno, AHORA, 9L);

		assertThat(RecepcionService.estadoOperativo(turno, enEspera, ABIERTA)).isEqualTo("EN_ATENCION");
		assertThat(RecepcionService.estadoOperativo(turno, enEspera, CERRADA)).isEqualTo("ATENDIDO");

		turno.marcarAusente(AHORA);
		assertThat(RecepcionService.estadoOperativo(turno, null, CERRADA)).isEqualTo("ATENDIDO");
	}

	@Test
	@DisplayName("ausente sin atencion, retirado si se fue sin ser atendido y presente con la recepcion abierta")
	void ausente_retirado_y_presente() {
		Turno ausente = turno(AHORA.minus(Duration.ofMinutes(5)));
		ausente.marcarAusente(AHORA);
		assertThat(RecepcionService.estadoOperativo(ausente, null, null)).isEqualTo("AUSENTE");

		Turno turno = turno(AHORA.plus(Duration.ofDays(1)));
		Recepcion recepcion = Recepcion.llegada(turno, AHORA, 9L);
		assertThat(RecepcionService.estadoOperativo(turno, recepcion, null)).isEqualTo("PRESENTE");

		recepcion.retirar("No quiso esperar", 9L, AHORA);
		assertThat(RecepcionService.estadoOperativo(turno, recepcion, null)).isEqualTo("RETIRADO");
	}

	@Test
	@DisplayName("el documento se muestra con su tipo si lo tiene, y una ficha que no esta no rompe la lista")
	void datos_del_paciente() {
		assertThat(RecepcionService.documentoDe(paciente("DNI", "30111222"))).isEqualTo("DNI 30111222");
		assertThat(RecepcionService.documentoDe(paciente(null, "30111222"))).isEqualTo("30111222");
		assertThat(RecepcionService.documentoDe(paciente("DNI", null))).isNull();
		assertThat(RecepcionService.documentoDe(null)).isNull();
		assertThat(RecepcionService.nombreDe(null)).isEqualTo("(ficha no disponible)");
		assertThat(RecepcionService.nombreDe(paciente(null, null))).isEqualTo("Perez, Ana");
	}

	private static PacienteSnapshot paciente(String tipo, String numero) {
		return new PacienteSnapshot(128L, 1L, "Perez", "Ana", tipo, numero, null, true, true, 5L, AHORA);
	}

	private static Turno turno(Instant inicio) {
		Turno turno = new Turno(1L, 7L, 42L, 128L, 31L, null, inicio, inicio.plus(Duration.ofHours(1)), 9L, AHORA, null, null);
		ReflectionTestUtils.setField(turno, "id", 301L);
		return turno;
	}
}
