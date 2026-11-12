package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.InstructoresDeClaseService;
import com.akine.activity.application.ProgramarClaseCommand;
import com.akine.activity.application.ReprogramarClaseCommand;
import com.akine.activity.application.ResultadoDeProgramacion;
import com.akine.activity.domain.exception.HorarioNoDisponibleException;
import com.akine.platform.spi.problem.ConflictoDeAgenda;
import com.akine.resource.application.BloqueAltaCommand;
import com.akine.resource.application.DisponibilidadService;
import com.akine.resource.application.ExcepcionAltaCommand;
import com.akine.resource.application.ExcepcionService;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.resource.domain.exception.ActividadReservadaEnConflictoException;
import com.akine.resource.domain.exception.BloqueSolapadoException;
import com.akine.resource.domain.exception.BloqueoConTurnosAfectadosException;
import com.akine.resource.domain.exception.DisponibilidadSuperpuestaException;
import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaOperativaService;
import com.akine.scheduling.application.AgendaOperativaView;
import com.akine.scheduling.application.AgendaOperativaView.ConflictoDeAgendaView;
import com.akine.scheduling.application.AltaDeSerieCommand;
import com.akine.scheduling.application.PrevisualizacionDeSerieService;
import com.akine.scheduling.application.PrevisualizacionDeSerieView;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.ResultadoDeReserva;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.DisponibilidadDeTurnosService;
import com.akine.scheduling.application.ValidacionDeHorarioCommand;
import com.akine.scheduling.application.ValidacionDeHorarioView;
import com.akine.scheduling.domain.ReglaDeRecurrencia;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La disponibilidad central por persona (contrato 0.158.0) contra MySQL real: aperturas validadas en
 * todas las sedes, instructores adicionales validados como el principal, mensajes de conflicto con la sede
 * real, conflictos historicos marcados y la carrera apertura–turno entre dos sedes.
 *
 * <p>Centro = {@code crear(1)} con horario los lunes de 09 a 13. Norte = otra sede de la misma persona,
 * sin horario (salvo donde se indica).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DisponibilidadCentralIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private ExcepcionService excepciones;
	@Autowired private DisponibilidadService disponibilidad;
	@Autowired private TurnoService turnoService;
	@Autowired private ClaseService claseService;
	@Autowired private InstructoresDeClaseService instructores;
	@Autowired private PrevisualizacionDeSerieService previsualizacion;
	@Autowired private AgendaOperativaService agendaOperativa;
	@Autowired private DisponibilidadDeTurnosService validacionDeHorario;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void setUp() {
		fixtures = new AgendaFixtures(jdbc);
	}

	// =================================================================================
	// Aperturas (punto 4)
	// =================================================================================

	@Test
	@DisplayName("Apertura en Norte superpuesta con el horario de Centro: 409 con la sede de Centro; con sustitucion, cierra Centro")
	void apertura_superpuesta_rechazo_y_sustitucion() {
		Fixture centro = fixtures.crear(1);
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, false);

		assertThatThrownBy(() -> abrir(norte, false))
				.isInstanceOfSatisfying(DisponibilidadSuperpuestaException.class, e -> {
					ConflictoDeAgenda primera = e.getSuperposiciones().getFirst();
					assertThat(primera.tipo()).isEqualTo(ConflictoDeAgenda.Tipo.DISPONIBILIDAD);
					assertThat(primera.consultorioId()).isEqualTo(centro.consultorioId());
					assertThat(primera.mismaSede()).isFalse();
					assertThat(primera.desde()).isEqualTo(a(10, 0));
					assertThat(primera.hasta()).isEqualTo(a(12, 0));
					assertThat(e.getMessage()).contains("en la sede " + nombre(centro)).contains("de 10:00 a 12:00");
				});
		assertThat(excepcionesVivas(norte, "APERTURA")).isZero();

		abrir(norte, true);

		assertThat(excepcionesVivas(norte, "APERTURA")).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM disponibilidad_excepcion
				 WHERE consultorio_id = ? AND membership_id = ? AND tipo = 'CIERRE' AND active = true
				   AND hora_desde = '10:00:00' AND hora_hasta = '12:00:00' AND fecha_desde = CAST(? AS date)
				""", Long.class, centro.consultorioId(), centro.profesionalMembershipId(), LUNES)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE consultorio_id = ? AND event_type = 'DISPONIBILIDAD_EXCEPCION_CREATED'
				   AND CAST(details AS text) LIKE '%SUSTITUCION_POR_APERTURA%'
				""", Long.class, centro.consultorioId())).isEqualTo(1);
		// Y Centro deja de ofrecer las 10:00: el cierre recorta su franja.
		assertThatThrownBy(() -> reservar(centro, a(10, 0)))
				.isInstanceOf(com.akine.scheduling.domain.exception.SlotNoDisponibleException.class);
	}

	@Test
	@DisplayName("D-1/D-2: el cierre de la sustitucion se lee como un cierre; darlo de baja o achicarlo es 409 con la sede de la apertura")
	void baja_o_achique_del_cierre_de_sustitucion() {
		Fixture centro = fixtures.crear(1);
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, false);
		abrir(norte, true);
		long cierre = jdbc.queryForObject("""
				SELECT id FROM disponibilidad_excepcion
				 WHERE consultorio_id = ? AND tipo = 'CIERRE' AND active = true
				""", Long.class, centro.consultorioId());

		// D-2: el historial lo muestra como un cierre normal, con el origen en castellano.
		var auditoria = jdbc.queryForMap("""
				SELECT reason, details ->> 'despues' AS despues FROM audit_event
				 WHERE entity_id = ? AND event_type = 'DISPONIBILIDAD_EXCEPCION_CREATED'
				""", cierre);
		assertThat(auditoria.get("despues")).isEqualTo("Cierre por bloqueo, 08/03/2027, 10:00–12:00");
		assertThat(auditoria.get("reason")).isEqualTo("Sustituida por una apertura en la sede " + nombre(norte));

		// D-1: la baja devolveria a Centro la franja que Norte ya ofrece.
		assertThatThrownBy(() -> excepciones.darDeBaja(actorDeResource(centro), centro.consultorioId(), cierre,
				"Probando la baja"))
				.isInstanceOfSatisfying(DisponibilidadSuperpuestaException.class, e -> {
					ConflictoDeAgenda primera = e.getSuperposiciones().getFirst();
					assertThat(primera.consultorioId()).isEqualTo(norte.consultorioId());
					assertThat(primera.desde()).isEqualTo(a(10, 0));
					assertThat(primera.hasta()).isEqualTo(a(12, 0));
					assertThat(e.getMessage()).contains("en la sede " + nombre(norte))
							.contains(DisponibilidadSuperpuestaException.AL_LIBERAR);
				});
		// Achicarlo a 10:00–10:30 libera 10:30–12:00: tambien 409.
		assertThatThrownBy(() -> excepciones.editar(actorDeResource(centro), centro.consultorioId(), cierre,
				new com.akine.resource.application.ExcepcionEdicionCommand(MotivoExcepcion.BLOQUEO, LUNES,
						LUNES.plusDays(1), LocalTime.of(10, 0), LocalTime.of(10, 30), null, 0, false)))
				.isInstanceOfSatisfying(DisponibilidadSuperpuestaException.class,
						e -> assertThat(e.getSuperposiciones().getFirst().desde()).isEqualTo(a(10, 30)));
		assertThat(excepcionesVivas(centro, "CIERRE")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT hora_hasta FROM disponibilidad_excepcion WHERE id = ?",
				LocalTime.class, cierre)).isEqualTo(LocalTime.of(12, 0));
	}

	@Test
	@DisplayName("D-1: la baja de un cierre de sede entera revalida a cada profesional con horario ahi")
	void baja_de_cierre_de_sede_revalida_a_cada_profesional() {
		Fixture centro = fixtures.crear(1);
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, false);
		long cierreDeSede = excepciones.crear(actorDeResource(centro), centro.consultorioId(), new ExcepcionAltaCommand(
				null, TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO, LUNES, LUNES.plusDays(1),
				LocalTime.of(10, 0), LocalTime.of(12, 0), null, null)).id();
		// Con Centro cerrado de 10 a 12, la apertura en Norte no pisa nada.
		abrir(norte, false);

		assertThatThrownBy(() -> excepciones.darDeBaja(actorDeResource(centro), centro.consultorioId(), cierreDeSede,
				"Probando la baja"))
				.isInstanceOfSatisfying(DisponibilidadSuperpuestaException.class,
						e -> assertThat(e.getSuperposiciones().getFirst().consultorioId()).isEqualTo(norte.consultorioId()));
		assertThat(excepcionesVivas(centro, "CIERRE")).isEqualTo(1);
	}

	@Test
	@DisplayName("D-3: editar devuelve la version nueva y un segundo guardado encadenado entra")
	void edicion_encadenada_con_la_version_devuelta() {
		Fixture centro = fixtures.crear(1);
		var alta = excepciones.crear(actorDeResource(centro), centro.consultorioId(), new ExcepcionAltaCommand(
				centro.profesionalMembershipId(), TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO, LUNES,
				LUNES.plusDays(1), LocalTime.of(12, 0), LocalTime.of(13, 0), null, null));

		var primera = excepciones.editar(actorDeResource(centro), centro.consultorioId(), alta.id(),
				new com.akine.resource.application.ExcepcionEdicionCommand(MotivoExcepcion.BLOQUEO, LUNES,
						LUNES.plusDays(1), LocalTime.of(12, 0), LocalTime.of(13, 0), "Primera", alta.version(), false));
		assertThat(primera.version()).isEqualTo(alta.version() + 1);
		var segunda = excepciones.editar(actorDeResource(centro), centro.consultorioId(), alta.id(),
				new com.akine.resource.application.ExcepcionEdicionCommand(MotivoExcepcion.BLOQUEO, LUNES,
						LUNES.plusDays(1), LocalTime.of(12, 0), LocalTime.of(13, 0), "Segunda", primera.version(), false));
		assertThat(segunda.version()).isEqualTo(jdbc.queryForObject(
				"SELECT version FROM disponibilidad_excepcion WHERE id = ?", Long.class, alta.id()));
	}

	@Test
	@DisplayName("Apertura contra un turno de la misma persona en otra sede: siempre 409, aun con sustitucion")
	void apertura_contra_turno_en_otra_sede() {
		Fixture centro = fixtures.crear(1);
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, false);
		ResultadoDeReserva turno = reservar(centro, a(10, 0));

		assertThatThrownBy(() -> abrir(norte, true))
				.isInstanceOfSatisfying(ActividadReservadaEnConflictoException.class, e -> {
					assertThat(e.getConflicto().tipo()).isEqualTo(ConflictoDeAgenda.Tipo.TURNO);
					assertThat(e.getConflicto().eventoId()).isEqualTo(turno.turno().id());
					assertThat(e.getConflicto().consultorioNombre()).isEqualTo(nombre(centro));
					assertThat(e.getMessage()).doesNotContain("Paciente");
				});
		assertThat(excepcionesVivas(norte, "APERTURA")).isZero();
		assertThat(excepcionesVivas(centro, "CIERRE")).isZero();
	}

	@Test
	@DisplayName("Apertura en Norte y turno en Centro a la vez: entra uno solo (lock de la persona, validacion repetida bajo el lock)")
	void carrera_apertura_contra_turno() {
		Fixture centro = fixtures.crear(1);
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, false);

		List<Desenlace<Object>> desenlaces = AgendaFixtures.enParalelo(List.<Callable<Object>>of(
				() -> { abrir(norte, true); return "apertura"; },
				() -> reservar(centro, a(10, 0))));

		assertThat(desenlaces).as("%s", desenlaces).filteredOn(d -> !d.fallo()).hasSize(1);
		assertThat(excepcionesVivas(norte, "APERTURA") + turnosVivos(centro)).isEqualTo(1);
	}

	@Test
	@DisplayName("D-5: el sobreturno no salva un turno de la misma persona en otra sede; en la misma sede si")
	void sobreturno_contra_turno_de_otra_sede() {
		Fixture centro = fixtures.crear(1);
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, true);
		ResultadoDeReserva enNorte = reservar(norte, a(10, 0));

		// La validacion previa no lo ofrece: lo ocupa la misma persona en Norte.
		ValidacionDeHorarioView validacion = validar(centro, a(10, 0));
		assertThat(validacion.causa()).isEqualTo("PROFESIONAL_OCUPADO");
		assertThat(validacion.conflictoConsultorioId()).isEqualTo(norte.consultorioId());
		assertThat(validacion.admiteSobreturno()).isFalse();

		// Y la reserva con sobreturno es 409 recurso-ocupado con el turno de Norte.
		assertThatThrownBy(() -> sobreturno(centro, centro.personaA(), a(10, 0)))
				.isInstanceOfSatisfying(RecursoOcupadoException.class, e -> {
					assertThat(e.getConflictoTipo()).isEqualTo("TURNO");
					assertThat(e.getTurnoEnConflictoId()).isEqualTo(enNorte.turno().id());
					assertThat(e.getConflicto().mismaSede()).isFalse();
					assertThat(e.getConflicto().consultorioId()).isEqualTo(norte.consultorioId());
					assertThat(e.getConflicto().consultorioNombre()).isEqualTo(nombre(norte));
					assertThat(e.getMessage()).contains("en la sede " + nombre(norte));
				});
		assertThat(turnosVivos(centro)).isZero();

		// Un turno de la MISMA sede si se sobreturna: ese es su sentido.
		reservar(centro, a(11, 0));
		ValidacionDeHorarioView mismaSede = validar(centro, a(11, 0));
		assertThat(mismaSede.estado()).isEqualTo("NO_DISPONIBLE");
		assertThat(mismaSede.admiteSobreturno()).isTrue();
		assertThat(sobreturno(centro, centro.personaB(), a(11, 0)).turno().sobreturno()).isTrue();
		assertThat(turnosVivos(centro)).isEqualTo(2);
	}

	@Test
	@DisplayName("Horario habitual en Norte que pisa el de Centro: bloque-solapado nombra Centro, no 'esta sede'")
	void bloque_solapado_con_la_sede_real() {
		Fixture centro = fixtures.crear(1);
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, false);

		assertThatThrownBy(() -> disponibilidad.crear(actorDeResource(norte), norte.consultorioId(),
				norte.profesionalMembershipId(), new BloqueAltaCommand(DayOfWeek.MONDAY.getValue(),
						LocalTime.of(12, 0), LocalTime.of(14, 0), null, null)))
				.isInstanceOfSatisfying(BloqueSolapadoException.class, e -> {
					assertThat(e.getConflictoConsultorioId()).isEqualTo(centro.consultorioId());
					assertThat(e.isConflictoMismaSede()).isFalse();
					assertThat(e.detalle()).contains("en la sede " + nombre(centro))
							.contains("los lunes de 09:00 a 13:00").doesNotContain("esta sede");
				});
	}

	// =================================================================================
	// Instructores (punto 5)
	// =================================================================================

	@Test
	@DisplayName("Instructor adicional bloqueado de 10:30 a 11:30 con clase de 10 a 11: 409 nombrandolo; y el cierre posterior informa la clase")
	void instructor_adicional_bloqueado() {
		Fixture centro = fixtures.crear(1);
		long adicional = fixtures.agregarProfesional(centro);
		long grupal = ofertaGrupal(centro);
		ResultadoDeProgramacion clase = programar(centro, grupal, a(10, 0), a(11, 0));
		excepciones.crear(actorDeResource(centro), centro.consultorioId(), new ExcepcionAltaCommand(adicional,
				TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO, LUNES, LUNES.plusDays(1), LocalTime.of(10, 30),
				LocalTime.of(11, 30), null, null));

		assertThatThrownBy(() -> instructores.reemplazar(actorDeActividad(centro), centro.consultorioId(),
				clase.clase().id(), List.of(adicional)))
				.isInstanceOfSatisfying(HorarioNoDisponibleException.class, e -> {
					assertThat(e.getConflicto().tipo()).isEqualTo(ConflictoDeAgenda.Tipo.BLOQUEO);
					assertThat(e.getConflicto().instructorMembershipId()).isEqualTo(adicional);
					assertThat(e.getConflicto().instructorNombre()).isNotBlank();
					assertThat(e.getMotivo()).startsWith("El instructor ").contains("de 10:30 a 11:30");
				});

		// Al reves: con el adicional ya asignado, un cierre suyo encima pide confirmacion e informa la clase.
		Fixture otro = fixtures.crear(1);
		long otroAdicional = fixtures.agregarProfesional(otro);
		ResultadoDeProgramacion otraClase = programar(otro, ofertaGrupal(otro), a(10, 0), a(11, 0));
		instructores.reemplazar(actorDeActividad(otro), otro.consultorioId(), otraClase.clase().id(),
				List.of(otroAdicional));
		assertThatThrownBy(() -> excepciones.crear(actorDeResource(otro), otro.consultorioId(),
				new ExcepcionAltaCommand(otroAdicional, TipoExcepcion.CIERRE, MotivoExcepcion.VACACIONES, LUNES,
						LUNES.plusDays(1), null, null, null, null)))
				.isInstanceOfSatisfying(BloqueoConTurnosAfectadosException.class, e -> assertThat(e.getClases())
						.singleElement().satisfies(c -> {
							assertThat(c.claseId()).isEqualTo(otraClase.clase().id());
							assertThat(c.instructorMembershipId()).isEqualTo(otroAdicional);
						}));
	}

	@Test
	@DisplayName("D-6: un adicional que no atiende en toda la clase es 409 con el instructor, DISPONIBILIDAD, la sede y la franja")
	void instructor_adicional_fuera_de_su_horario() {
		Fixture centro = fixtures.crear(1);
		long adicional = fixtures.agregarProfesional(centro);
		// Su horario termina 10:30: no cubre la clase de 10 a 11.
		jdbc.update("UPDATE profesional_disponibilidad SET hora_hasta = '10:30:00' WHERE membership_id = ?", adicional);
		ResultadoDeProgramacion clase = programar(centro, ofertaGrupal(centro), a(10, 0), a(11, 0));

		assertThatThrownBy(() -> instructores.reemplazar(actorDeActividad(centro), centro.consultorioId(),
				clase.clase().id(), List.of(adicional)))
				.isInstanceOfSatisfying(HorarioNoDisponibleException.class, e -> {
					ConflictoDeAgenda conflicto = e.getConflicto();
					assertThat(conflicto).isNotNull();
					assertThat(conflicto.tipo()).isEqualTo(ConflictoDeAgenda.Tipo.DISPONIBILIDAD);
					assertThat(conflicto.instructorMembershipId()).isEqualTo(adicional);
					assertThat(conflicto.instructorNombre()).isNotBlank();
					assertThat(conflicto.consultorioId()).isEqualTo(centro.consultorioId());
					assertThat(conflicto.consultorioNombre()).isEqualTo(nombre(centro));
					assertThat(conflicto.mismaSede()).isTrue();
					assertThat(conflicto.desde()).isEqualTo(a(10, 0));
					assertThat(conflicto.hasta()).isEqualTo(a(11, 0));
					assertThat(conflicto.propiedades()).containsEntry("conflictoTipo", "DISPONIBILIDAD")
							.containsEntry("instructorMembershipId", adicional)
							.containsKeys("instructorNombre", "conflictoInicio", "conflictoFin");
					assertThat(e.getMotivo()).startsWith("El instructor ").contains("no atiende en esta sede");
				});
	}

	@Test
	@DisplayName("Reprogramar la clase valida a todos los instructores: un turno del adicional a la hora nueva es 409 con el instructor")
	void reprogramar_valida_a_todos_los_instructores() {
		Fixture centro = fixtures.crear(1);
		long adicional = fixtures.agregarProfesional(centro);
		ResultadoDeProgramacion clase = programar(centro, ofertaGrupal(centro), a(10, 0), a(11, 0));
		instructores.reemplazar(actorDeActividad(centro), centro.consultorioId(), clase.clase().id(),
				List.of(adicional));
		ResultadoDeReserva turno = turnoService.reservar(centro.actor(), centro.consultorioId(), centro.ofertaId(),
				new ReservaCommand(centro.personaA(), a(12, 0), adicional, null));
		long version = jdbc.queryForObject("SELECT version FROM clase_programada WHERE id = ?", Long.class,
				clase.clase().id());

		assertThatThrownBy(() -> claseService.reprogramar(actorDeActividad(centro), centro.consultorioId(),
				clase.clase().id(), new ReprogramarClaseCommand(a(12, 0), a(13, 0),
						centro.profesionalMembershipId(), 5, version, null)))
				.isInstanceOfSatisfying(com.akine.activity.domain.exception.RecursoOcupadoException.class, e -> {
					assertThat(e.getConflicto().tipo()).isEqualTo(ConflictoDeAgenda.Tipo.TURNO);
					assertThat(e.getConflicto().eventoId()).isEqualTo(turno.turno().id());
					assertThat(e.getConflicto().instructorMembershipId()).isEqualTo(adicional);
					assertThat(e.getConflicto().mismaSede()).isTrue();
					assertThat(e.getMessage()).contains("en esta sede");
				});
	}

	// =================================================================================
	// Recurrencia, mensajes (punto 3) y conflictos historicos (punto 6)
	// =================================================================================

	@Test
	@DisplayName("Serie en Norte con una clase en Centro el segundo lunes: esa ocurrencia se reporta con fecha y sede")
	void recurrencia_con_conflicto_futuro() {
		Fixture centro = fixtures.crear(1);
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, true);
		programar(centro, ofertaGrupal(centro), a(LUNES.plusWeeks(1), 10, 0), a(LUNES.plusWeeks(1), 11, 0));

		PrevisualizacionDeSerieView vista = previsualizacion.previsualizar(norte.actor(), norte.consultorioId(),
				new AltaDeSerieCommand(norte.ofertaId(), norte.personaA(), norte.profesionalMembershipId(),
						new ReglaDeRecurrencia(Set.of(DayOfWeek.MONDAY), LocalTime.of(10, 0), LUNES, null, 3), null));

		assertThat(vista.ocurrencias()).extracting(PrevisualizacionDeSerieView.OcurrenciaPrevistaView::estado)
				.containsExactly("DISPONIBLE", "NO_DISPONIBLE", "DISPONIBLE");
		assertThat(vista.ocurrencias().get(1).motivo())
				.isEqualTo("El profesional ya tiene una clase el 15/03/2027 de 10:00 a 11:00 en la sede "
						+ nombre(centro) + ".");
	}

	@Test
	@DisplayName("La agenda operativa marca la doble oferta y la clase con instructor en cierre que ya estaban guardadas")
	void conflictos_historicos_marcados() {
		Fixture centro = fixtures.crear(1);
		// Doble oferta heredada: el mismo horario en Norte, cargado directo como lo dejaba el sistema viejo.
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, true);
		ResultadoDeProgramacion clase = programar(centro, ofertaGrupal(centro), a(10, 0), a(11, 0));
		// Un bloqueo cargado directo encima de la clase (dato heredado).
		jdbc.update("""
				INSERT INTO disponibilidad_excepcion (organization_id, consultorio_id, membership_id, tipo, motivo,
				       fecha_desde, fecha_hasta, hora_desde, hora_hasta, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 'CIERRE', 'BLOQUEO', CAST(? AS date), CAST(? AS date), '10:30:00', '11:30:00', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", centro.organizationId(), centro.consultorioId(), centro.profesionalMembershipId(), LUNES,
				LUNES.plusDays(1));

		AgendaOperativaView operativa = agendaOperativa.ver(centro.actor(), centro.consultorioId(), LUNES,
				LUNES.plusDays(1), null, null, null, false);

		assertThat(operativa.conflictos()).filteredOn(c -> ConflictoDeAgendaView.CLASE_EN_CIERRE.equals(c.tipo()))
				.singleElement().satisfies(c -> {
					assertThat(c.claseId()).isEqualTo(clase.clase().id());
					assertThat(c.causa()).isEqualTo("BLOQUEO");
					assertThat(c.inicio()).isEqualTo(a(10, 30));
				});
		assertThat(operativa.conflictos()).filteredOn(c -> ConflictoDeAgendaView.DISPONIBILIDAD_DOBLE.equals(c.tipo()))
				.isNotEmpty()
				.allSatisfy(c -> assertThat(c.otraSedeConsultorioId()).isEqualTo(norte.consultorioId()));
	}

	@Test
	@DisplayName("Aislamiento: la misma cuenta en otra organizacion no genera conflictos aca")
	void aislamiento_entre_organizaciones() {
		Fixture centro = fixtures.crear(1);
		Fixture norte = fixtures.otraSedeDelMismoProfesional(centro, false);
		// La misma cuenta, profesional en OTRA organizacion, con el mismo horario y un turno a las 10:30.
		Fixture ajena = fixtures.crear(1);
		long cuenta = jdbc.queryForObject("SELECT account_id FROM membership WHERE id = ?", Long.class,
				centro.profesionalMembershipId());
		jdbc.update("UPDATE membership SET account_id = ? WHERE id = ?", cuenta, ajena.profesionalMembershipId());
		reservar(ajena, a(10, 0));

		// La apertura en Norte choca con Centro (misma organizacion) y con nada de la ajena.
		assertThatThrownBy(() -> abrir(norte, false))
				.isInstanceOfSatisfying(DisponibilidadSuperpuestaException.class, e -> assertThat(e.getSuperposiciones())
						.allSatisfy(s -> assertThat(s.consultorioId()).isEqualTo(centro.consultorioId())));
		abrir(norte, true);
		assertThat(turnosVivos(ajena)).isEqualTo(1);
	}

	// =================================================================================

	private void abrir(Fixture sede, boolean sustituir) {
		excepciones.crear(actorDeResource(sede), sede.consultorioId(), new ExcepcionAltaCommand(
				sede.profesionalMembershipId(), TipoExcepcion.APERTURA, MotivoExcepcion.AMPLIACION, LUNES,
				LUNES.plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), null, null, false, sustituir));
	}

	private ResultadoDeReserva reservar(Fixture sede, Instant inicio) {
		return turnoService.reservar(sede.actor(), sede.consultorioId(), sede.ofertaId(),
				new ReservaCommand(sede.personaA(), inicio, sede.profesionalMembershipId(), null));
	}

	private ResultadoDeReserva sobreturno(Fixture sede, long personaId, Instant inicio) {
		return turnoService.reservar(sede.actor(), sede.consultorioId(), sede.ofertaId(),
				new ReservaCommand(personaId, inicio, sede.profesionalMembershipId(), null, null, null, null, null,
						null, "Urgencia", null));
	}

	private ValidacionDeHorarioView validar(Fixture sede, Instant inicio) {
		return validacionDeHorario.validar(sede.actor(), sede.consultorioId(), new ValidacionDeHorarioCommand(
				sede.ofertaId(), inicio, sede.profesionalMembershipId(), null, null, null, null, null));
	}

	private ResultadoDeProgramacion programar(Fixture sede, long ofertaId, Instant inicio, Instant fin) {
		return claseService.programar(actorDeActividad(sede), sede.consultorioId(), ofertaId,
				new ProgramarClaseCommand(inicio, fin, sede.profesionalMembershipId(), 5, "Clase sintetica", null, null));
	}

	private static com.akine.activity.application.OperatingActor actorDeActividad(Fixture sede) {
		return new com.akine.activity.application.OperatingActor(
				sede.actor().accountId(), false, sede.organizationId(), sede.consultorioId());
	}

	private static com.akine.resource.application.OperatingActor actorDeResource(Fixture sede) {
		return new com.akine.resource.application.OperatingActor(
				sede.actor().accountId(), false, sede.organizationId(), sede.consultorioId());
	}

	private static Instant a(int hora, int minuto) {
		return a(LUNES, hora, minuto);
	}

	private static Instant a(LocalDate dia, int hora, int minuto) {
		return dia.atTime(hora, minuto).atZone(ZONA).toInstant().truncatedTo(ChronoUnit.MINUTES);
	}

	private String nombre(Fixture sede) {
		return jdbc.queryForObject("SELECT name FROM consultorio WHERE id = ?", String.class, sede.consultorioId());
	}

	private long turnosVivos(Fixture sede) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM turno WHERE consultorio_id = ? AND deleted_at IS NULL",
				Long.class, sede.consultorioId());
	}

	private long excepcionesVivas(Fixture sede, String tipo) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM disponibilidad_excepcion WHERE consultorio_id = ? AND tipo = ? AND active = true",
				Long.class, sede.consultorioId(), tipo);
	}

	/** Una oferta GRUPAL de la sede, sin habilitaciones (= todos), con profesional y sin box. */
	private long ofertaGrupal(Fixture sede) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", "DISP-" + sufijo, "Servicio " + sufijo);
		long servicio = jdbc.queryForObject("SELECT id FROM servicio WHERE codigo = ?", Long.class, "DISP-" + sufijo);
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, 10, false, false, false, true, false,
				        CAST(CURRENT_DATE - INTERVAL '5 years' AS date), true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", sede.organizationId(), sede.consultorioId(), servicio, "Grupal " + sufijo);
		return jdbc.queryForObject(
				"SELECT id FROM oferta_servicio_consultorio WHERE organization_id = ? AND nombre_comercial = ?",
				Long.class, sede.organizationId(), "Grupal " + sufijo);
	}
}
