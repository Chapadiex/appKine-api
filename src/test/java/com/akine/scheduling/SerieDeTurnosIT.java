package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AlcanceDeSerieView;
import com.akine.scheduling.application.AltaDeSerieCommand;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.OperacionDeSerieCommand;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.SerieDeTurnosService;
import com.akine.scheduling.application.SeriePagina;
import com.akine.scheduling.application.SerieResumenView;
import com.akine.scheduling.application.SerieView;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.domain.AlcanceDeSerie;
import com.akine.scheduling.domain.EstadoDeSerie;
import com.akine.scheduling.domain.ReglaDeRecurrencia;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.OcurrenciaSinLugarException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SerieNotAccessibleException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import static com.akine.scheduling.AgendaFixtures.causaEs;
import static com.akine.scheduling.AgendaFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Series de turnos contra MySQL real (AKINE E-3).
 *
 * <p>El primero es el caso que rompe el diseno (§9.8 del diseno): una serie y una reserva suelta
 * que pisan la misma ocurrencia. Si ganan las dos, hay dos pacientes encima; si la serie entra a
 * medias, hay una serie de 3 de 4 que nadie decidio. Lo que lo impide es el {@code FOR UPDATE} de
 * {@code agenda_sede} en {@code READ_COMMITTED}, y eso solo lo prueba InnoDB.
 *
 * <p>Los demas son de base aunque no sean concurrentes: que cancelar por alcance libere el lugar
 * depende de {@code deleted_at} y de las consultas de solapamiento, y que reprogramar "una semana
 * para adelante" no choque contra la propia serie depende del orden en que los UPDATE llegan al
 * motor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class SerieDeTurnosIT {

	/** Cuatro lunes de marzo de 2027, sin feriados. Lejos en el futuro para que no venzan. */
	private static final LocalDate PRIMER_LUNES = LocalDate.of(2027, 3, 8);

	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private SerieDeTurnosService serieService;
	@Autowired private TurnoService turnoService;
	@Autowired private CicloDeTurnoService cicloService;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("V74 ejecuto: existe turno_serie y turno.serie_id")
	void la_migracion_v70_ejecuto() {
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = 'turno' AND column_name = 'serie_id'
				""", Long.class)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.tables
				 WHERE table_schema = DATABASE() AND table_name = 'turno_serie'
				""", Long.class)).isEqualTo(1L);
	}

	@Test
	@DisplayName("una serie y una reserva que pisa una ocurrencia compiten: gana una sola, y la serie nunca entra a medias")
	void alta_de_serie_compite_con_una_reserva_que_pisa_una_ocurrencia() {
		Fixture fixture = fixtures.crear(1);
		// La tercera ocurrencia de la serie es el lunes 22 a las 9: ahi apunta la reserva suelta.
		Instant lunes22 = hora(PRIMER_LUNES.plusWeeks(2), 9);

		Callable<Object> serie = () -> crearSerie(fixture, fixture.personaA(), 4);
		Callable<Object> reserva = () -> turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(fixture.personaB(), lunes22, fixture.profesionalMembershipId(), null))
				.turno();

		List<Desenlace<Object>> desenlaces = enParalelo(List.of(serie, reserva));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("una sola de las dos entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(turnosVivosQueEmpiezanEn(fixture, lunes22))
				.as("y en el lunes 22 a las 9 hay UNA sola fila viva")
				.isEqualTo(1);

		long series = jdbc.queryForObject(
				"SELECT COUNT(*) FROM turno_serie WHERE organization_id = ?", Long.class,
				fixture.organizationId());
		long deSerie = jdbc.queryForObject(
				"SELECT COUNT(*) FROM turno WHERE organization_id = ? AND serie_id IS NOT NULL",
				Long.class, fixture.organizationId());

		if (desenlaces.get(0).fallo()) {
			// Perdio la serie: todo o nada. Ni la regla ni ninguna de las otras tres ocurrencias.
			assertThat(causaEs(desenlaces.get(0).error(), OcurrenciaSinLugarException.class))
					.as("la serie falla nombrando la ocurrencia: %s", desenlaces.get(0))
					.isTrue();
			assertThat(series).as("ninguna serie quedo").isZero();
			assertThat(deSerie).as("ninguna ocurrencia quedo").isZero();
		} else {
			assertThat(causaEs(desenlaces.get(1).error(), SlotCompletoException.class)
					|| causaEs(desenlaces.get(1).error(), RecursoOcupadoException.class))
					.as("la reserva recibe un 409 de agenda: %s", desenlaces.get(1))
					.isTrue();
			assertThat(series).isEqualTo(1);
			assertThat(deSerie).as("las cuatro ocurrencias, completas").isEqualTo(4);
		}
	}

	@Test
	@DisplayName("con una ocurrencia ya ocupada, la serie no crea nada y el error nombra el dia")
	void serie_con_una_ocurrencia_ocupada_no_crea_nada() {
		Fixture fixture = fixtures.crear(1);
		Instant lunes15 = hora(PRIMER_LUNES.plusWeeks(1), 9);
		turnoService.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(fixture.personaB(), lunes15, fixture.profesionalMembershipId(), null));

		assertThatThrownBy(() -> crearSerie(fixture, fixture.personaA(), 4))
				.isInstanceOfSatisfying(OcurrenciaSinLugarException.class,
						error -> assertThat(error.getOcurrenciaInicio()).isEqualTo(lunes15));

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM turno WHERE organization_id = ? AND persona_id = ?",
				Long.class, fixture.organizationId(), fixture.personaA()))
				.as("ni la ocurrencia del lunes 8, que si tenia lugar: todo o nada")
				.isZero();
	}

	@Test
	@DisplayName("cancelar 'este y los siguientes' deja los anteriores intactos y libera los lugares")
	void cancelar_este_y_los_siguientes() {
		Fixture fixture = fixtures.crear(1);
		SerieView serie = crearSerie(fixture, fixture.personaA(), 4);
		TurnoView tercero = serie.turnos().get(2);

		AlcanceDeSerieView previa = serieService.previsualizar(
				fixture.actor(), fixture.consultorioId(), serie.id(),
				AlcanceDeSerie.ESTE_Y_SIGUIENTES, tercero.id());
		assertThat(previa.afectados()).hasSize(2);

		AlcanceDeSerieView hecha = serieService.cancelar(
				fixture.actor(), fixture.consultorioId(), serie.id(),
				OperacionDeSerieCommand.cancelacion(AlcanceDeSerie.ESTE_Y_SIGUIENTES, tercero.id(),
						"El paciente termina el tratamiento antes", previa.afectados().size()));

		assertThat(hecha.afectados()).extracting(TurnoView::id)
				.containsExactly(serie.turnos().get(2).id(), serie.turnos().get(3).id());
		assertThat(hecha.afectados()).extracting(TurnoView::estado).containsOnly("CANCELADO");

		SerieView despues = serieService.ver(fixture.actor(), fixture.consultorioId(), serie.id());
		assertThat(despues.turnos()).extracting(TurnoView::estado)
				.as("los dos primeros quedaron como estaban; las cuatro filas siguen ahi")
				.containsExactly("RESERVADO", "RESERVADO", "CANCELADO", "CANCELADO");
		assertThat(despues.turnos().get(0).version())
				.as("y ni siquiera cambio su version")
				.isEqualTo(serie.turnos().get(0).version());

		// Liberar el lugar se prueba ocupandolo: el lunes 22 a las 9 vuelve a aceptar una reserva.
		TurnoView reemplazo = turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(fixture.personaB(), tercero.inicio(),
						fixture.profesionalMembershipId(), null))
				.turno();
		assertThat(reemplazo.serieId()).isNull();

		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM turno_evento e JOIN turno t ON t.id = e.turno_id
				 WHERE t.serie_id = ? AND e.tipo = 'CANCELACION'
				   AND e.motivo = 'El paciente termina el tratamiento antes'
				""", Long.class, serie.id()))
				.as("cada turno cancelado lleva su propio evento con el motivo")
				.isEqualTo(2L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'TURNO_SERIE_CANCELADA' AND entity_id = ?
				""", Long.class, fixture.organizationId(), serie.id()))
				.as("y la serie registra el alcance en la auditoria")
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("una cantidad confirmada que ya no es la real no cancela nada")
	void la_cantidad_confirmada_desactualizada_no_cancela_nada() {
		Fixture fixture = fixtures.crear(1);
		SerieView serie = crearSerie(fixture, fixture.personaA(), 3);

		assertThatThrownBy(() -> serieService.cancelar(
				fixture.actor(), fixture.consultorioId(), serie.id(),
				OperacionDeSerieCommand.cancelacion(AlcanceDeSerie.TODA_LA_SERIE, null, "Baja", 2)))
				.isInstanceOf(OptimisticLockingFailureException.class);

		assertThat(serieService.ver(fixture.actor(), fixture.consultorioId(), serie.id()).turnos())
				.extracting(TurnoView::estado)
				.containsOnly("RESERVADO");
	}

	@Test
	@DisplayName("mover 'este y los siguientes' una semana para adelante no choca contra la propia serie")
	void reprogramar_este_y_los_siguientes_una_semana() {
		Fixture fixture = fixtures.crear(1);
		SerieView serie = crearSerie(fixture, fixture.personaA(), 4);
		TurnoView segundo = serie.turnos().get(1);

		// El destino del segundo es el lugar actual del tercero, y el del tercero el del cuarto.
		AlcanceDeSerieView hecha = serieService.reprogramar(
				fixture.actor(), fixture.consultorioId(), serie.id(),
				new OperacionDeSerieCommand(AlcanceDeSerie.ESTE_Y_SIGUIENTES, segundo.id(),
						"El paciente viaja esa semana", 3,
						segundo.inicio().plusSeconds(7L * 24 * 3600), null));

		assertThat(hecha.afectados()).extracting(TurnoView::inicio).containsExactly(
				hora(PRIMER_LUNES.plusWeeks(2), 9),
				hora(PRIMER_LUNES.plusWeeks(3), 9),
				hora(PRIMER_LUNES.plusWeeks(4), 9));
		assertThat(hecha.afectados()).extracting(TurnoView::id)
				.as("son los MISMOS turnos, movidos: reprogramar no cancela y crea")
				.containsExactly(serie.turnos().get(1).id(), serie.turnos().get(2).id(),
						serie.turnos().get(3).id());
		assertThat(contarVivos(fixture)).as("ninguna fila nueva ni dada de baja").isEqualTo(4);
		assertThat(turnosVivosQueEmpiezanEn(fixture, hora(PRIMER_LUNES.plusWeeks(1), 9)))
				.as("el lunes 15 quedo libre")
				.isZero();
	}

	@Test
	@DisplayName("una ausencia en la primera ocurrencia no toca la serie ni las siguientes")
	void la_primera_ausencia_preserva_los_futuros() {
		Fixture fixture = fixtures.crear(1);
		SerieView serie = crearSerie(fixture, fixture.personaA(), 3);
		long primero = serie.turnos().get(0).id();
		// El turno no puede llegar al pasado por el servicio —la serie exige futuro—: se corre la
		// fila a la semana pasada, como la dejaria el tiempo.
		jdbc.update("""
				UPDATE turno SET inicio = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY),
				                 fin = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY) + INTERVAL 60 MINUTE
				 WHERE id = ?
				""", primero);

		cicloService.marcarAusente(fixture.actor(), fixture.consultorioId(), primero, "No vino", 0L);

		SerieView despues = serieService.ver(fixture.actor(), fixture.consultorioId(), serie.id());
		assertThat(despues.turnos()).extracting(TurnoView::estado)
				.containsExactly("AUSENTE", "RESERVADO", "RESERVADO");

		// Y "toda la serie" desde ahi omite al ausente y cancela solo los dos futuros.
		AlcanceDeSerieView previa = serieService.previsualizar(
				fixture.actor(), fixture.consultorioId(), serie.id(), AlcanceDeSerie.TODA_LA_SERIE, null);
		assertThat(previa.afectados()).hasSize(2);
		assertThat(previa.omitidos()).extracting(AlcanceDeSerieView.Omitido::motivo)
				.containsExactly("ESTADO_TERMINAL");
	}

	@Test
	@DisplayName("una serie de otro tenant es 404: ni se ve ni se cancela")
	void aislamiento_de_tenant() {
		Fixture duena = fixtures.crear(1);
		Fixture ajena = fixtures.crear(1);
		SerieView serie = crearSerie(duena, duena.personaA(), 2);

		assertThatThrownBy(() -> serieService.ver(ajena.actor(), ajena.consultorioId(), serie.id()))
				.isInstanceOf(SerieNotAccessibleException.class);
		assertThatThrownBy(() -> serieService.cancelar(ajena.actor(), ajena.consultorioId(), serie.id(),
				OperacionDeSerieCommand.cancelacion(AlcanceDeSerie.TODA_LA_SERIE, null, "Intrusion", 2)))
				.isInstanceOf(SerieNotAccessibleException.class);

		assertThat(serieService.ver(duena.actor(), duena.consultorioId(), serie.id()).turnos())
				.extracting(TurnoView::estado)
				.containsOnly("RESERVADO");
	}

	@Test
	@DisplayName("bandeja (E-8): pagina por sede, filtra por persona y por estado derivado, y no ve otro tenant")
	void bandeja_de_series() {
		Fixture duena = fixtures.crear(1);
		Fixture ajena = fixtures.crear(1);
		SerieView vigente = crearSerie(duena, duena.personaA(), 3);
		SerieView cancelada = serieService.crear(duena.actor(), duena.consultorioId(),
				new AltaDeSerieCommand(duena.ofertaId(), duena.personaB(), duena.profesionalMembershipId(),
						new ReglaDeRecurrencia(Set.of(DayOfWeek.MONDAY), LocalTime.of(11, 0),
								PRIMER_LUNES, null, 2),
						null)).serie();
		serieService.cancelar(duena.actor(), duena.consultorioId(), cancelada.id(),
				OperacionDeSerieCommand.cancelacion(AlcanceDeSerie.TODA_LA_SERIE, null, "Alta medica", 2));

		SeriePagina todas = serieService.listar(duena.actor(), duena.consultorioId(), null, null, 0, 20);
		assertThat(todas.total()).isEqualTo(2);
		assertThat(todas.contenido()).extracting(SerieResumenView::id)
				.as("mas nuevas primero")
				.containsExactly(cancelada.id(), vigente.id());
		SerieResumenView filaVigente = todas.contenido().get(1);
		assertThat(filaVigente.estado()).isEqualTo(EstadoDeSerie.VIGENTE);
		assertThat(filaVigente.totalTurnos()).isEqualTo(3);
		assertThat(filaVigente.turnosPendientes()).isEqualTo(3);
		assertThat(filaVigente.proximoTurnoInicio()).isEqualTo(hora(PRIMER_LUNES, 9));
		assertThat(filaVigente.personaNombre()).startsWith("Paciente");
		assertThat(filaVigente.ofertaNombre()).isNotBlank();
		assertThat(filaVigente.diasSemana()).containsExactly(1);
		SerieResumenView filaCancelada = todas.contenido().get(0);
		assertThat(filaCancelada.estado()).isEqualTo(EstadoDeSerie.CANCELADA);
		assertThat(filaCancelada.turnosPendientes()).isZero();
		assertThat(filaCancelada.totalTurnos()).as("los cancelados se cuentan: siguen siendo de la serie").isEqualTo(2);
		assertThat(filaCancelada.proximoTurnoInicio()).isNull();

		assertThat(serieService.listar(duena.actor(), duena.consultorioId(), duena.personaA(), null, 0, 20)
				.contenido()).extracting(SerieResumenView::id).containsExactly(vigente.id());
		assertThat(serieService.listar(duena.actor(), duena.consultorioId(), null, EstadoDeSerie.VIGENTE, 0, 20)
				.contenido()).extracting(SerieResumenView::id).containsExactly(vigente.id());
		SeriePagina canceladas = serieService.listar(
				duena.actor(), duena.consultorioId(), null, EstadoDeSerie.CANCELADA, 0, 20);
		assertThat(canceladas.total()).isEqualTo(1);
		assertThat(canceladas.contenido()).extracting(SerieResumenView::id).containsExactly(cancelada.id());

		SeriePagina segunda = serieService.listar(duena.actor(), duena.consultorioId(), null, null, 1, 1);
		assertThat(segunda.total()).isEqualTo(2);
		assertThat(segunda.contenido()).extracting(SerieResumenView::id).containsExactly(vigente.id());

		// Aislamiento: la sede ajena no ve nada, la sede de otro tenant es 404 y filtrar por un
		// paciente de otro tenant da una pagina vacia.
		assertThat(serieService.listar(ajena.actor(), ajena.consultorioId(), null, null, 0, 20).total()).isZero();
		assertThat(serieService.listar(ajena.actor(), ajena.consultorioId(), duena.personaA(), null, 0, 20)
				.contenido()).isEmpty();
		assertThatThrownBy(() -> serieService.listar(ajena.actor(), duena.consultorioId(), null, null, 0, 20))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
	}

	@Test
	@DisplayName("bandeja (E-8b, DP-20): cancelada entera o desde la mitad es CANCELADA; agotada por fecha es FINALIZADA")
	void bandeja_distingue_cancelada_de_finalizada() {
		Fixture fixture = fixtures.crear(1);

		// Cancelada entera: "toda la serie" antes de empezar.
		SerieView entera = crearSerie(fixture, fixture.personaA(), 2, 9);
		cancelar(fixture, entera, AlcanceDeSerie.TODA_LA_SERIE, null, 2);

		// Cortada desde la mitad con una atencion previa: el primero se atendio (paso), el segundo
		// y el tercero se cancelaron con "este y los siguientes".
		SerieView cortada = crearSerie(fixture, fixture.personaB(), 3, 10);
		cancelar(fixture, cortada, AlcanceDeSerie.ESTE_Y_SIGUIENTES, cortada.turnos().get(1).id(), 2);
		assertThat(estadoEnLaBandeja(fixture, cortada.id()))
				.as("mientras el primero no paso, le queda un pendiente")
				.isEqualTo(EstadoDeSerie.VIGENTE);
		alPasado(cortada.turnos().get(0).id(), 21);

		// Agotada por fecha, con una cancelacion suelta en el medio: el ultimo turno paso.
		SerieView agotada = crearSerie(fixture, fixture.personaC(), 3, 11);
		cancelar(fixture, agotada, AlcanceDeSerie.ESTE, agotada.turnos().get(1).id(), 1);
		alPasado(agotada.turnos().get(0).id(), 21);
		alPasado(agotada.turnos().get(1).id(), 14);
		alPasado(agotada.turnos().get(2).id(), 7);

		SerieView vigente = crearSerie(fixture, fixture.personaA(), 2, 12);

		SeriePagina todas = serieService.listar(fixture.actor(), fixture.consultorioId(), null, null, 0, 20);
		assertThat(todas.contenido())
				.extracting(SerieResumenView::id, SerieResumenView::estado)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(vigente.id(), EstadoDeSerie.VIGENTE),
						org.assertj.core.groups.Tuple.tuple(agotada.id(), EstadoDeSerie.FINALIZADA),
						org.assertj.core.groups.Tuple.tuple(cortada.id(), EstadoDeSerie.CANCELADA),
						org.assertj.core.groups.Tuple.tuple(entera.id(), EstadoDeSerie.CANCELADA));

		// El filtro de la base y el resumen de la fila usan la misma regla: cada filtro devuelve
		// exactamente las filas que el listado sin filtro muestra con ese estado, y el total cuadra.
		for (EstadoDeSerie estado : EstadoDeSerie.values()) {
			SeriePagina filtradas = serieService.listar(
					fixture.actor(), fixture.consultorioId(), null, estado, 0, 20);
			List<Long> esperadas = todas.contenido().stream()
					.filter(fila -> fila.estado() == estado).map(SerieResumenView::id).toList();
			assertThat(filtradas.contenido()).extracting(SerieResumenView::id)
					.as("filtro %s", estado).containsExactlyElementsOf(esperadas);
			assertThat(filtradas.contenido()).extracting(SerieResumenView::estado).containsOnly(estado);
			assertThat(filtradas.total()).as("total de %s", estado).isEqualTo(esperadas.size());
		}
	}

	@Test
	@DisplayName("el reintento con la misma clave devuelve la misma serie sin crear otra")
	void reintento_idempotente() {
		Fixture fixture = fixtures.crear(1);
		AltaDeSerieCommand pedido = alta(fixture, fixture.personaA(), 2, "serie-it-reintento");

		var primero = serieService.crear(fixture.actor(), fixture.consultorioId(), pedido);
		var segundo = serieService.crear(fixture.actor(), fixture.consultorioId(), pedido);

		assertThat(primero.creada()).isTrue();
		assertThat(segundo.creada()).isFalse();
		assertThat(segundo.serie().id()).isEqualTo(primero.serie().id());
		assertThat(contarVivos(fixture)).isEqualTo(2);
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private SerieView crearSerie(Fixture fixture, long personaId, int cantidad) {
		return serieService.crear(fixture.actor(), fixture.consultorioId(),
				alta(fixture, personaId, cantidad, null)).serie();
	}

	private SerieView crearSerie(Fixture fixture, long personaId, int cantidad, int horaLocal) {
		return serieService.crear(fixture.actor(), fixture.consultorioId(),
				new AltaDeSerieCommand(fixture.ofertaId(), personaId, fixture.profesionalMembershipId(),
						new ReglaDeRecurrencia(Set.of(DayOfWeek.MONDAY), LocalTime.of(horaLocal, 0),
								PRIMER_LUNES, null, cantidad),
						null)).serie();
	}

	private void cancelar(Fixture fixture, SerieView serie, AlcanceDeSerie alcance, Long pivote, int cantidad) {
		serieService.cancelar(fixture.actor(), fixture.consultorioId(), serie.id(),
				OperacionDeSerieCommand.cancelacion(alcance, pivote, "Alta medica", cantidad));
	}

	/** Corre la fila al pasado, como la dejaria el tiempo: el servicio no reserva ni cancela en el pasado. */
	private void alPasado(long turnoId, int dias) {
		jdbc.update("""
				UPDATE turno SET inicio = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL ? DAY),
				                 fin = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL ? DAY) + INTERVAL 60 MINUTE
				 WHERE id = ?
				""", dias, dias, turnoId);
	}

	private EstadoDeSerie estadoEnLaBandeja(Fixture fixture, long serieId) {
		return serieService.listar(fixture.actor(), fixture.consultorioId(), null, null, 0, 20)
				.contenido().stream().filter(fila -> fila.id() == serieId)
				.map(SerieResumenView::estado).findFirst().orElseThrow();
	}

	private static AltaDeSerieCommand alta(Fixture fixture, long personaId, int cantidad, String clave) {
		return new AltaDeSerieCommand(fixture.ofertaId(), personaId, fixture.profesionalMembershipId(),
				new ReglaDeRecurrencia(Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0),
						PRIMER_LUNES, null, cantidad),
				clave);
	}

	private static Instant hora(LocalDate fecha, int horaLocal) {
		return fecha.atTime(horaLocal, 0).atZone(ZONA).toInstant();
	}

	private long contarVivos(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM turno
				 WHERE organization_id = ? AND oferta_id = ? AND deleted_at IS NULL
				""", Long.class, fixture.organizationId(), fixture.ofertaId());
	}

	/** Ver el homonimo de {@code TurnoCicloConcurrenteIT}: el instante viaja como texto UTC. */
	private long turnosVivosQueEmpiezanEn(Fixture fixture, Instant inicio) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM turno
				 WHERE organization_id = ? AND oferta_id = ? AND deleted_at IS NULL
				   AND inicio = ?
				""", Long.class, fixture.organizationId(), fixture.ofertaId(),
				inicio.atZone(ZoneId.of("UTC")).toLocalDateTime());
	}
}
