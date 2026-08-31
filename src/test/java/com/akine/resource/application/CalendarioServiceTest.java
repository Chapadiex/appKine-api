package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.Feriado;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * La politica de calendario de la sede: como nace, como se edita y —lo que mas importa— que
 * efectivamente llegue al calculo.
 *
 * <h2>Por que el puerto de calendario es un doble EN MEMORIA y no un mock</h2>
 *
 * <p>Las dos preguntas de esta clase son sobre ESTADO que sobrevive entre llamadas: "la fila se
 * crea a demanda y la segunda vez se reusa" y "lo que guardo el PUT es lo que lee el calculo".
 * Un mock de Mockito no tiene estado: habria que stubear la respuesta de la segunda llamada a
 * mano, y entonces el test estaria afirmando su propio stub en vez de la persistencia. El doble
 * en memoria guarda de verdad, que es lo unico que hace que estas dos pruebas prueben algo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalendarioServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long MEMBERSHIP_ID = 30L;
	private static final long ACCOUNT_ID = 40L;
	private static final long BLOQUE_ID = 501L;

	private static final String ZONA = "America/Argentina/Cordoba";

	/** 2026-07-09, Dia de la Independencia. Cae JUEVES. */
	private static final LocalDate FERIADO = LocalDate.of(2026, 7, 9);

	private static final int DIA_JUEVES = 4;

	@Mock
	private FeriadoRepositoryPort feriados;

	@Mock
	private ConsultorioDirectory consultorioDirectory;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private BloqueDisponibilidadRepositoryPort bloques;

	@Mock
	private DisponibilidadExcepcionRepositoryPort excepciones;

	@Mock
	private ConsultorioMembershipDirectory membershipDirectory;

	private final CalendarioEnMemoria calendarios = new CalendarioEnMemoria();

	private CalendarioService service;
	private DisponibilidadEfectivaService efectiva;

	private final OperatingActor actor =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		// El iniciador real sobre el doble en memoria: su REQUIRES_NEW es un no-op sin proxy de
		// Spring, y lo que estos tests necesitan es que la fila quede creada de verdad.
		service = new CalendarioService(
				calendarios, new CalendarioSedeIniciador(calendarios), feriados,
				consultorioDirectory, permissionGuard, auditTrail);
		efectiva = new DisponibilidadEfectivaService(
				bloques, excepciones, feriados, calendarios,
				consultorioDirectory, membershipDirectory, permissionGuard);

		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede Sintetica", ZONA, true)));
		given(feriados.findByPaisAndFechaBetween(anyString(), any(), any())).willReturn(List.of());
		given(bloques.findVigentesEn(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(excepciones.findQueCubren(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID)).willReturn(Optional.of(
				new ConsultorioMembershipSnapshot(
						MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, "PROFESIONAL", "ACTIVA",
						Instant.parse("2020-01-01T00:00:00Z"), null, true, true)));
	}

	// =================================================================================
	// La fila se crea a demanda
	// =================================================================================

	@Test
	@DisplayName("La LECTURA no crea la fila: devuelve los valores por defecto y lo dice")
	void la_lectura_no_crea_la_fila() {
		CalendarioView vista = service.ver(actor, CONSULTORIO_ID, FERIADO, FERIADO.plusDays(1));

		assertThat(vista.existePersistida())
				.as("un GET que escribe no puede correr en una transaccion de solo lectura")
				.isFalse();
		assertThat(vista.cierraPorFeriado()).isTrue();
		assertThat(vista.pais()).isEqualTo(CalendarioSede.PAIS_POR_DEFECTO);
		assertThat(calendarios.filas()).isEmpty();
	}

	@Test
	@DisplayName("La primera edicion crea la fila con los valores por defecto, y la segunda la reusa")
	void la_edicion_crea_la_fila_a_demanda_y_la_segunda_la_reusa() {
		service.actualizar(actor, CONSULTORIO_ID, null, false);

		assertThat(calendarios.filas())
				.as("la fila nace en la primera edicion, no antes")
				.hasSize(1);
		assertThat(calendarios.filas().getFirst().getPais())
				.as("el pais no viajaba en el pedido: queda el default de V23")
				.isEqualTo(CalendarioSede.PAIS_POR_DEFECTO);

		service.actualizar(actor, CONSULTORIO_ID, null, true);

		assertThat(calendarios.filas())
				.as("una segunda fila para la misma sede violaria uk_consultorio_calendario_sede, "
						+ "y ademas partiria en dos el punto de serializacion de los writes")
				.hasSize(1);
		assertThat(calendarios.filas().getFirst().isCierraPorFeriado()).isTrue();
	}

	@Test
	@DisplayName("La edicion parcial deja intacto lo que no viajo, y audita solo lo que cambio")
	void la_edicion_es_parcial_y_audita_el_cambio() {
		service.actualizar(actor, CONSULTORIO_ID, "UY", false);

		CalendarioView vista = service.actualizar(actor, CONSULTORIO_ID, null, null);

		assertThat(vista.pais())
				.as("cierraPorFeriado nulo NO es false y pais nulo NO borra el pais")
				.isEqualTo("UY");
		assertThat(vista.cierraPorFeriado()).isFalse();

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail, org.mockito.Mockito.times(2)).record(auditoria.capture());
		assertThat(auditoria.getAllValues().getFirst().details())
				.containsEntry("cierraPorFeriado", "true -> false")
				.containsEntry("pais", "AR -> UY");
		assertThat(auditoria.getAllValues().getLast().details())
				.as("la segunda edicion no cambio nada: no hay detalle que declarar")
				.isEmpty();
	}

	// =================================================================================
	// La politica llega al calculo
	// =================================================================================

	@Test
	@DisplayName("Apagar cierraPorFeriado por el servicio deja de vaciar los feriados en la disponibilidad efectiva")
	void apagar_cierra_por_feriado_deja_de_vaciar_los_feriados() {
		darFeriado();
		darBloqueDelJueves();

		// Con la politica por defecto —sin fila todavia— el feriado cierra el dia.
		DisponibilidadEfectivaView conCierre = efectiva.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, FERIADO, FERIADO.plusDays(1));
		assertThat(conCierre.dias().getFirst().franjas()).isEmpty();
		assertThat(conCierre.dias().getFirst().razonVacio()).isEqualTo("FERIADO");

		// El centro declara que atiende los feriados. Es el unico paso del test: nadie stubea la
		// politica a mano, la escribe el servicio que la pantalla usa.
		service.actualizar(actor, CONSULTORIO_ID, null, false);

		DisponibilidadEfectivaView sinCierre = efectiva.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, FERIADO, FERIADO.plusDays(1));

		assertThat(sinCierre.dias().getFirst().franjas())
				.as("si la politica de la sede no estuviera cableada al calculo, este dia seguiria "
						+ "vacio y nada mas lo delataria")
				.hasSize(1);
		assertThat(sinCierre.dias().getFirst().razonVacio()).isNull();
		assertThat(sinCierre.dias().getFirst().esFeriado())
				.as("sigue siendo feriado: que el centro atienda es otro dato")
				.isTrue();
		assertThat(sinCierre.dias().getFirst().feriadoNombre()).isEqualTo("Dia de la Independencia");
	}

	@Test
	@DisplayName("El pais de la politica es el que se usa para buscar los feriados")
	void el_pais_de_la_politica_decide_que_feriados_se_buscan() {
		service.actualizar(actor, CONSULTORIO_ID, "UY", null);

		service.ver(actor, CONSULTORIO_ID, FERIADO, FERIADO.plusDays(1));

		// El extremo superior se resta un dia: findByPaisAndFechaBetween es INCLUSIVO en los dos
		// extremos y la ventana de la etapa es [desde, hasta).
		verify(feriados).findByPaisAndFechaBetween("UY", FERIADO, FERIADO);
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	private void darFeriado() {
		Feriado feriado = new Feriado("AR", FERIADO, "Dia de la Independencia", "INAMOVIBLE");
		ReflectionTestUtils.setField(feriado, "id", 900L);
		given(feriados.findByPaisAndFechaBetween(anyString(), any(), any()))
				.willReturn(List.of(feriado));
	}

	private void darBloqueDelJueves() {
		BloqueDisponibilidad bloque = new BloqueDisponibilidad(
				ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, DIA_JUEVES,
				LocalTime.of(9, 0), LocalTime.of(13, 0), LocalDate.of(2020, 1, 1), null);
		ReflectionTestUtils.setField(bloque, "id", BLOQUE_ID);
		given(bloques.findVigentesEn(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(bloque));
	}

	/**
	 * Doble en memoria del puerto de calendario. Guarda de verdad, que es justamente lo que un
	 * mock no hace y lo que estos tests necesitan.
	 *
	 * <p>{@code lockByScope} devuelve lo mismo que {@code findByScope}: el {@code FOR UPDATE} es
	 * una garantia del motor y no se puede simular en memoria. Que el lock se PIDA antes de la
	 * primera lectura es lo unico verificable sin base, y eso ya lo fija el test del servicio
	 * hermano con {@code InOrder}.
	 */
	private static final class CalendarioEnMemoria implements CalendarioSedeRepositoryPort {

		private final List<CalendarioSede> filas = new ArrayList<>();
		private final AtomicLong secuencia = new AtomicLong(800L);

		@Override
		public Optional<CalendarioSede> findByScope(Long organizationId, Long consultorioId) {
			return filas.stream()
					.filter(fila -> fila.getOrganizationId().equals(organizationId)
							&& fila.getConsultorioId().equals(consultorioId))
					.findFirst();
		}

		@Override
		public Optional<CalendarioSede> lockByScope(Long organizationId, Long consultorioId) {
			return findByScope(organizationId, consultorioId);
		}

		@Override
		public CalendarioSede save(CalendarioSede calendario) {
			if (calendario.getId() == null) {
				ReflectionTestUtils.setField(calendario, "id", secuencia.incrementAndGet());
				filas.add(calendario);
			}
			return calendario;
		}

		/** Equivalente en memoria del {@code INSERT ... ON DUPLICATE KEY UPDATE}: no lanza nunca. */
		@Override
		public void crearSiFalta(long organizationId, long consultorioId) {
			if (findByScope(organizationId, consultorioId).isEmpty()) {
				save(new CalendarioSede(organizationId, consultorioId));
			}
		}

		List<CalendarioSede> filas() {
			return List.copyOf(filas);
		}
	}
}
