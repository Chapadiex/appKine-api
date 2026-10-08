package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.resource.application.DisponibilidadEfectivaView.DiaEfectivo;
import com.akine.resource.application.DisponibilidadEfectivaView.FranjaResuelta;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.Feriado;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.resource.domain.exception.VentanaDemasiadoAmpliaException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.HorarioGeneralRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los invariantes de la proyeccion de disponibilidad efectiva, sin base de datos.
 *
 * <h2>Que prueba este test y que prueba el del calculador</h2>
 *
 * <p>{@code DisponibilidadEfectivaCalculatorTest} prueba la aritmetica de intervalos en hora
 * local. Este prueba las CINCO cosas que el calculador declara que no hace y que quedaron a cargo
 * del servicio: el huso, {@code FIN_DE_DIA}, el filtro por sede, la vigencia de la membership y el
 * nombre del feriado. Por eso el calculador NO se mockea: se usa el real, porque un doble dejaria
 * sin probar justamente la costura entre las dos mitades.
 *
 * <p><b>El filtro por sede no se puede probar aca</b> y no esta entre estos tests: lo que hay que
 * verificar es que la CONSULTA lleve el predicado por {@code consultorio_id}, y con los puertos
 * mockeados el test estaria comprobando su propio stub. Vive en
 * {@code DisponibilidadEfectivaSedeIT}, contra MySQL real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisponibilidadEfectivaServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long MEMBERSHIP_ID = 30L;
	private static final long ACCOUNT_ID = 40L;
	private static final long BLOQUE_ID = 501L;
	private static final long CIERRE_ID = 601L;

	private static final String ZONA_AR = "America/Argentina/Buenos_Aires";
	private static final String ZONA_CON_DST = "America/New_York";

	/** Martes. El bloque base de casi todos los tests cae aca. */
	private static final LocalDate MARTES = LocalDate.of(2026, 3, 3);

	private static final int DIA_MARTES = 2;
	private static final int DIA_DOMINGO = 7;

	@Mock
	private BloqueDisponibilidadRepositoryPort bloques;

	@Mock
	private DisponibilidadExcepcionRepositoryPort excepciones;

	@Mock
	private FeriadoRepositoryPort feriados;

	@Mock
	private CalendarioSedeRepositoryPort calendarios;

	@Mock
	private HorarioGeneralRepositoryPort horarios;

	@Mock
	private ConsultorioDirectory consultorioDirectory;

	@Mock
	private ConsultorioMembershipDirectory membershipDirectory;

	@Mock
	private PermissionGuard permissionGuard;

	private DisponibilidadEfectivaService service;

	private final OperatingActor actor =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new DisponibilidadEfectivaService(
				bloques, excepciones, feriados, calendarios,
				consultorioDirectory, membershipDirectory, permissionGuard, horarios);

		enZona(ZONA_AR);
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID)).willReturn(Optional.of(vinculada()));
		given(calendarios.findByScope(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.empty());
		given(feriados.findByPaisAndFechaBetween(anyString(), any(), any())).willReturn(List.of());
		given(bloques.findVigentesEn(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(excepciones.findQueCubren(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
	}

	// =================================================================================
	// La ventana
	// =================================================================================

	@Test
	@DisplayName("Una ventana mayor al tope se rechaza con 400 y ni siquiera consulta los bloques")
	void una_ventana_mayor_al_tope_da_400() {
		LocalDate desde = LocalDate.of(2026, 1, 1);

		assertThatThrownBy(() -> service.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, desde, desde.plusDays(367)))
				.isInstanceOf(VentanaDemasiadoAmpliaException.class)
				.hasMessageContaining("366");

		// Sin tope, un desde=1970 seria un scan completo: el punto del tope es no llegar a leer.
		verifyNoInteractions(bloques);
		verifyNoInteractions(excepciones);
	}

	@Test
	@DisplayName("Una ventana de exactamente 366 dias entra: un anio bisiesto completo es legitimo")
	void una_ventana_de_exactamente_el_tope_entra() {
		LocalDate desde = LocalDate.of(2026, 1, 1);

		DisponibilidadEfectivaView efectiva = service.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, desde, desde.plusDays(366));

		assertThat(efectiva.dias()).hasSize(366);
	}

	@Test
	@DisplayName("Una ventana invertida o vacia se rechaza con 400: el extremo superior es EXCLUSIVO")
	void hasta_menor_o_igual_que_desde_da_400() {
		assertThatThrownBy(() -> service.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, MARTES, MARTES))
				.as("hasta == desde son cero dias, no un dia")
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> service.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, MARTES, MARTES.minusDays(1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// =================================================================================
	// Vigencia de la membership
	// =================================================================================

	@Test
	@DisplayName("Una membership desvinculada devuelve la ventana entera sin franjas, y no es un error")
	void una_membership_desvinculada_devuelve_vacio_y_no_error() {
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID)).willReturn(Optional.of(desvinculada()));

		DisponibilidadEfectivaView efectiva = service.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, MARTES, MARTES.plusDays(7));

		assertThat(efectiva.dias())
				.as("la grilla se dibuja igual: la pantalla tiene que poder mostrar los siete dias")
				.hasSize(7);
		assertThat(efectiva.dias()).allSatisfy(dia ->
				assertThat(dia.franjas()).isEmpty());
	}

	@Test
	@DisplayName("Una membership desvinculada no toca ninguna de sus reglas: la historia se conserva")
	void una_membership_desvinculada_no_borra_sus_bloques() {
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID)).willReturn(Optional.of(desvinculada()));

		service.efectiva(actor, CONSULTORIO_ID, MEMBERSHIP_ID, MARTES, MARTES.plusDays(7));

		// RN-M05-003: desvincular a un profesional no borra sus bloques ni su autoria. Esta
		// lectura no escribe nada, y ademas ni siquiera los lee: no hay nada que calcular.
		verifyNoInteractions(bloques);
		verifyNoInteractions(excepciones);
	}

	// =================================================================================
	// El huso de la sede
	// =================================================================================

	@Test
	@DisplayName("Las horas locales se convierten con el huso de la sede, no con UTC")
	void las_horas_locales_se_convierten_con_el_huso_de_la_sede() {
		darBloque(DIA_MARTES, LocalTime.of(9, 0), LocalTime.of(13, 0));

		DiaEfectivo dia = unicoDia(MARTES);

		assertThat(dia.franjas()).hasSize(1);
		FranjaResuelta franja = dia.franjas().getFirst();
		// Buenos Aires es UTC-03 todo el anio: las 09:00 locales son las 12:00Z.
		assertThat(franja.desde()).isEqualTo(Instant.parse("2026-03-03T12:00:00Z"));
		assertThat(franja.hasta()).isEqualTo(Instant.parse("2026-03-03T16:00:00Z"));
		assertThat(dia.fecha()).isEqualTo(MARTES);
	}

	@Test
	@DisplayName("Un bloque hasta el fin del dia termina en el inicio del dia siguiente, no un nanosegundo antes")
	void un_bloque_hasta_el_fin_del_dia_termina_en_el_inicio_del_siguiente() {
		darBloque(DIA_MARTES, LocalTime.of(22, 0), IntervaloLocal.FIN_DE_DIA);

		FranjaResuelta franja = unicoDia(MARTES).franjas().getFirst();

		// FIN_DE_DIA es LocalTime.MAX (23:59:59.999999999). Convertirlo literal daria
		// 2026-03-04T02:59:59.999999999Z y perderia el ultimo slot del dia sin que nadie lo note.
		assertThat(franja.hasta()).isEqualTo(Instant.parse("2026-03-04T03:00:00Z"));
		assertThat(franja.hasta().getNano()).isZero();
	}

	@Test
	@DisplayName("En el hueco de DST la hora local que no existe se corre hacia adelante")
	void un_huso_con_dst_resuelve_el_hueco_corriendo_hacia_adelante() {
		enZona(ZONA_CON_DST);
		LocalDate adelanto = LocalDate.of(2026, 3, 8); // domingo: el reloj salta de 02:00 a 03:00
		darBloque(DIA_DOMINGO, LocalTime.of(2, 0), LocalTime.of(4, 0));

		FranjaResuelta franja = unicoDia(adelanto).franjas().getFirst();

		// Las 02:00 locales NO EXISTEN ese dia. ZonedDateTime las corre a las 03:00 EDT (-04).
		assertThat(franja.desde()).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
		assertThat(franja.hasta()).isEqualTo(Instant.parse("2026-03-08T08:00:00Z"));
	}

	@Test
	@DisplayName("En el solapamiento de DST la hora local repetida se resuelve con el PRIMER offset")
	void un_huso_con_dst_resuelve_el_solapamiento_con_el_primer_offset() {
		enZona(ZONA_CON_DST);
		LocalDate atraso = LocalDate.of(2026, 11, 1); // domingo: las 01:00 ocurren dos veces
		darBloque(DIA_DOMINGO, LocalTime.of(1, 0), LocalTime.of(3, 0));

		FranjaResuelta franja = unicoDia(atraso).franjas().getFirst();

		// Primer offset: EDT (-04), o sea 05:00Z. El segundo seria EST (-05) = 06:00Z, y elegirlo
		// duplicaria la manana.
		assertThat(franja.desde()).isEqualTo(Instant.parse("2026-11-01T05:00:00Z"));
		// Las 03:00 ya son inequivocas: EST (-05).
		assertThat(franja.hasta()).isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
	}

	// =================================================================================
	// Feriados
	// =================================================================================

	@Test
	@DisplayName("Un feriado no cierra el dia si la sede tiene cierraPorFeriado en false")
	void un_feriado_no_cierra_si_la_sede_tiene_cierra_por_feriado_false() {
		given(calendarios.findByScope(ORG_ID, CONSULTORIO_ID)).willReturn(
				Optional.of(new CalendarioSede(ORG_ID, CONSULTORIO_ID, "AR", false)));
		darFeriado(MARTES, "Feriado Sintetico");
		darBloque(DIA_MARTES, LocalTime.of(9, 0), LocalTime.of(13, 0));

		DiaEfectivo dia = unicoDia(MARTES);

		assertThat(dia.esFeriado())
				.as("sigue siendo feriado aunque el centro atienda: son dos datos distintos")
				.isTrue();
		assertThat(dia.franjas()).hasSize(1);
		assertThat(dia.razonVacio()).isNull();
	}

	@Test
	@DisplayName("La respuesta trae el nombre del feriado que cerro el dia, que es el unico dato que el calculador no tiene")
	void la_respuesta_incluye_el_nombre_del_feriado_del_dia() {
		// Sin fila de politica: el default de V23 es cerrar los feriados.
		darFeriado(MARTES, "Dia de la Bandera");
		darBloque(DIA_MARTES, LocalTime.of(9, 0), LocalTime.of(13, 0));

		DiaEfectivo dia = unicoDia(MARTES);

		assertThat(dia.franjas()).isEmpty();
		assertThat(dia.razonVacio()).isEqualTo("FERIADO");
		assertThat(dia.feriadoNombre())
				.as("el calculador recibe un Set<LocalDate> sin nombres: sin este campo la "
						+ "pantalla dice cerrado y no puede decir por que")
				.isEqualTo("Dia de la Bandera");
		assertThat(dia.reglaVacio())
				.as("un feriado se resuelve por fecha y no tiene id de excepcion que ofrecer")
				.isNull();
	}

	// =================================================================================
	// Trazabilidad de la regla
	// =================================================================================

	@Test
	@DisplayName("Cada franja viaja con su origen, con lo que la recorto y con el id de su regla")
	void cada_franja_viaja_con_su_origen_y_su_regla() {
		darBloque(DIA_MARTES, LocalTime.of(9, 0), LocalTime.of(13, 0));
		darCierre(MARTES, LocalTime.of(11, 0), LocalTime.of(12, 0));

		DiaEfectivo dia = unicoDia(MARTES);

		assertThat(dia.franjas())
				.as("un cierre en el medio parte la franja en dos y las dos siguen siendo atencion")
				.hasSize(2);
		assertThat(dia.franjas()).allSatisfy(franja -> {
			assertThat(franja.origen()).isEqualTo("BLOQUE");
			assertThat(franja.recortadoPor()).isEqualTo("CIERRE");
			assertThat(franja.reglaId())
					.as("el id que viaja es el de la regla que PRODUJO la franja, para linkearla")
					.isEqualTo(BLOQUE_ID);
		});
		assertThat(dia.franjas().getFirst().hasta())
				.isEqualTo(Instant.parse("2026-03-03T14:00:00Z"));
		assertThat(dia.franjas().getLast().desde())
				.isEqualTo(Instant.parse("2026-03-03T15:00:00Z"));
	}

	@Test
	@DisplayName("La zona con la que se convirtio viaja en la respuesta")
	void la_zona_de_la_sede_viaja_en_la_respuesta() {
		DisponibilidadEfectivaView efectiva = service.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, MARTES, MARTES.plusDays(1));

		assertThat(efectiva.timezone()).isEqualTo(ZONA_AR);
		assertThat(efectiva.membershipId()).isEqualTo(MEMBERSHIP_ID);
		assertThat(efectiva.consultorioId()).isEqualTo(CONSULTORIO_ID);
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	private DiaEfectivo unicoDia(LocalDate fecha) {
		DisponibilidadEfectivaView efectiva =
				service.efectiva(actor, CONSULTORIO_ID, MEMBERSHIP_ID, fecha, fecha.plusDays(1));
		assertThat(efectiva.dias()).hasSize(1);
		return efectiva.dias().getFirst();
	}

	private void enZona(String zona) {
		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede Sintetica", zona, true)));
	}

	private void darBloque(int diaSemana, LocalTime desde, LocalTime hasta) {
		BloqueDisponibilidad bloque = new BloqueDisponibilidad(
				ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, diaSemana, desde, hasta,
				LocalDate.of(2020, 1, 1), null);
		ReflectionTestUtils.setField(bloque, "id", BLOQUE_ID);
		given(bloques.findVigentesEn(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(bloque));
	}

	private void darCierre(LocalDate fecha, LocalTime desde, LocalTime hasta) {
		DisponibilidadExcepcion cierre = new DisponibilidadExcepcion(
				ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID,
				TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO,
				fecha, fecha.plusDays(1), desde, hasta, null, null);
		ReflectionTestUtils.setField(cierre, "id", CIERRE_ID);
		given(excepciones.findQueCubren(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(cierre));
	}

	private void darFeriado(LocalDate fecha, String nombre) {
		Feriado feriado = new Feriado("AR", fecha, nombre, "INAMOVIBLE");
		ReflectionTestUtils.setField(feriado, "id", 900L);
		given(feriados.findByPaisAndFechaBetween(anyString(), any(), any()))
				.willReturn(List.of(feriado));
	}

	private static ConsultorioMembershipSnapshot vinculada() {
		return new ConsultorioMembershipSnapshot(
				MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, "PROFESIONAL", "ACTIVA",
				Instant.parse("2020-01-01T00:00:00Z"), null, true, true);
	}

	/** Vinculo revocado: {@code habilitada = false}, que es lo que RN-M05-003 deja atras. */
	private static ConsultorioMembershipSnapshot desvinculada() {
		return new ConsultorioMembershipSnapshot(
				MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, "PROFESIONAL", "REVOCADA",
				Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2021-01-01T00:00:00Z"),
				true, false);
	}

	/**
	 * Ruling R13: la vigencia se evalua DIA POR DIA.
	 *
	 * <p>El caso concreto que motivo el ruling: un vinculo que termina el 15 y una consulta de
	 * todo el mes. Con el control grueso —solapamiento contra la ventana entera— la membership
	 * "cubre la ventana" y sus bloques salian los 31 dias, asi que el sistema ofrecia turnos el 20
	 * con alguien que ya no trabaja en el centro. No fallaba nada: cada franja seguia trayendo un
	 * reglaId legitimo.
	 */
	@Test
	@DisplayName("Una membership que vence a mitad de la ventana atiende antes del vencimiento y no despues")
	void una_membership_que_vence_a_mitad_de_ventana_no_atiende_despues() {
		// Vinculo hasta el 11 de marzo a las 00:00 de la sede (03:00Z): el martes 10 lo cubre y
		// el martes 17 no.
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID)).willReturn(Optional.of(
				new ConsultorioMembershipSnapshot(
						MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, "PROFESIONAL", "ACTIVA",
						Instant.parse("2020-01-01T00:00:00Z"),
						Instant.parse("2026-03-11T03:00:00Z"), true, true)));
		darBloque(DIA_MARTES, LocalTime.of(9, 0), LocalTime.of(13, 0));

		DisponibilidadEfectivaView efectiva = service.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID,
				LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));

		DiaEfectivo antes = diaDe(efectiva, LocalDate.of(2026, 3, 10));
		assertThat(antes.franjas())
				.as("el martes 10 el vinculo seguia vigente: atiende")
				.hasSize(1);
		assertThat(antes.razonVacio()).isNull();

		DiaEfectivo despues = diaDe(efectiva, LocalDate.of(2026, 3, 17));
		assertThat(despues.franjas())
				.as("el martes 17 ya estaba desvinculado: con el control grueso este dia traia "
						+ "una franja de 09 a 13 y el motor de agenda la iba a reservar")
				.isEmpty();
		assertThat(despues.razonVacio())
				.as("y el dia vacio tiene que decir POR QUE: sin VINCULO es indistinguible de un "
						+ "dia en el que nadie cargo horario")
				.isEqualTo("VINCULO");
		assertThat(despues.reglaVacio())
				.as("un vinculo vencido no tiene id de excepcion que ofrecer")
				.isNull();
	}

	@Test
	@DisplayName("Un dia anterior al alta del vinculo tambien se vacia con VINCULO")
	void un_dia_anterior_al_alta_del_vinculo_se_vacia_con_vinculo() {
		// Se incorpora el 11 de marzo: el martes 3 todavia no estaba.
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID)).willReturn(Optional.of(
				new ConsultorioMembershipSnapshot(
						MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, "PROFESIONAL", "ACTIVA",
						Instant.parse("2026-03-11T03:00:00Z"), null, true, true)));
		darBloque(DIA_MARTES, LocalTime.of(9, 0), LocalTime.of(13, 0));

		DisponibilidadEfectivaView efectiva = service.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID,
				LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));

		assertThat(diaDe(efectiva, LocalDate.of(2026, 3, 3)).razonVacio()).isEqualTo("VINCULO");
		assertThat(diaDe(efectiva, LocalDate.of(2026, 3, 17)).franjas()).hasSize(1);
	}

	@Test
	@DisplayName("La membership que no cubre ni un dia de la ventana explica sus dias con VINCULO igual que el recorte fino")
	void el_atajo_grueso_explica_los_dias_con_la_misma_razon_que_el_recorte_fino() {
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID)).willReturn(Optional.of(desvinculada()));

		DisponibilidadEfectivaView efectiva = service.efectiva(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, MARTES, MARTES.plusDays(7));

		assertThat(efectiva.dias()).allSatisfy(dia ->
				assertThat(dia.razonVacio()).isEqualTo("VINCULO"));
	}

	private static DiaEfectivo diaDe(DisponibilidadEfectivaView efectiva, LocalDate fecha) {
		return efectiva.dias().stream()
				.filter(dia -> dia.fecha().equals(fecha))
				.findFirst()
				.orElseThrow(() -> new AssertionError("La ventana no trajo el dia " + fecha));
	}

	@Test
	@DisplayName("Un cierre de SEDE vacia el dia de un profesional que no tiene ninguna excepcion propia")
	void un_cierre_de_sede_alcanza_al_profesional_sin_excepciones_propias() {
		darBloque(DIA_MARTES, LocalTime.of(9, 0), LocalTime.of(13, 0));

		// membershipId nulo: alcance SEDE ENTERA. La consulta lo devuelve junto con las del
		// profesional a proposito; si el servicio no le pasara el membershipId al calculador, el
		// calculador no podria distinguir esto de la ausencia de otro colega.
		DisponibilidadExcepcion deSede = new DisponibilidadExcepcion(
				ORG_ID, CONSULTORIO_ID, null,
				TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO,
				MARTES, MARTES.plusDays(1), null, null, null, null);
		ReflectionTestUtils.setField(deSede, "id", 777L);
		given(excepciones.findQueCubren(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(deSede));

		DiaEfectivo dia = unicoDia(MARTES);

		assertThat(dia.franjas())
				.as("el corte de luz de la sede cierra a todos, tengan o no una ausencia cargada")
				.isEmpty();
		assertThat(dia.razonVacio()).isEqualTo("CIERRE");
		assertThat(dia.reglaVacio())
				.as("y la pantalla puede linkear la excepcion de sede que lo explica")
				.isEqualTo(777L);
	}

	@Test
	@DisplayName("La excepcion de OTRO profesional no toca la disponibilidad de este")
	void la_excepcion_de_otro_profesional_no_afecta_a_este() {
		darBloque(DIA_MARTES, LocalTime.of(9, 0), LocalTime.of(13, 0));

		DisponibilidadExcepcion deUnColega = new DisponibilidadExcepcion(
				ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID + 1,
				TipoExcepcion.CIERRE, MotivoExcepcion.LICENCIA,
				MARTES, MARTES.plusDays(1), null, null, null, null);
		ReflectionTestUtils.setField(deUnColega, "id", 778L);
		given(excepciones.findQueCubren(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of(deUnColega));

		assertThat(unicoDia(MARTES).franjas())
				.as("la licencia de un colega no es una licencia de esta persona")
				.hasSize(1);
	}
}
