package com.akine.offering.application;

import com.akine.offering.domain.EsquemaCobro;
import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.OfertaPractica;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.exception.PracticaNoElegibleException;
import com.akine.offering.domain.exception.PracticaPrincipalInvalidaException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaPracticaRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

/**
 * Las reglas del puente Oferta-Practica, sin base de datos (A-9, DP-11).
 *
 * <p>Se cubre lo que falla en silencio: el diff del reemplazo, la coherencia de la principal, que
 * solo se validen contra el catalogo las practicas que ENTRAN, y sobre todo el <b>orden de
 * escritura</b> —bajas y desmarcado con flush antes de cualquier alta o marca—, sin el cual el
 * reemplazo legitimo choca contra {@code uk_oferta_practica_principal} en MySQL. Que la base
 * efectivamente lo rechace lo prueba {@code OfertaPracticaIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfertaPracticaServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 3L;
	private static final long OFERTA_ID = 34L;
	private static final long ACCOUNT_ID = 170L;
	private static final long VERSION = 4L;
	private static final long PRACTICA_A = 51L;
	private static final long PRACTICA_B = 52L;
	private static final long PRACTICA_C = 53L;
	private static final long PRACTICA_AJENA = 99L;

	@Mock private OfertaRepositoryPort ofertas;
	@Mock private OfertaPracticaRepositoryPort practicas;
	@Mock private ConsultorioDirectory consultorioDirectory;
	@Mock private AccountContextDirectory accountContextDirectory;
	@Mock private CatalogoDirectory catalogo;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private OfertaPracticaService service;
	private final OperatingActor admin = new OperatingActor(ACCOUNT_ID, false, ORG, SEDE);

	/** Lo que la tabla "tiene" durante cada test. */
	private final List<OfertaPractica> filas = new ArrayList<>();
	private long proximoId = 1;

	@BeforeEach
	void setUp() {
		service = new OfertaPracticaService(ofertas, practicas, consultorioDirectory,
				accountContextDirectory, catalogo, permissionGuard, auditTrail);

		given(consultorioDirectory.find(ORG, SEDE))
				.willReturn(Optional.of(new ConsultorioSnapshot(SEDE, ORG, "Sede", "UTC", true)));
		given(accountContextDirectory.hasActiveMembership(ACCOUNT_ID, ORG)).willReturn(true);
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(oferta()));
		given(ofertas.findWithLockByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(oferta()));
		given(ofertas.bloquearParaConfigurar(OFERTA_ID, ORG, SEDE)).willReturn(Optional.of(VERSION));

		for (long id : new long[] {PRACTICA_A, PRACTICA_B, PRACTICA_C}) {
			given(catalogo.findPractica(eq(ORG), eq(id), any()))
					.willReturn(Optional.of(practica(id, true)));
		}
		given(catalogo.findPractica(eq(ORG), eq(PRACTICA_AJENA), any())).willReturn(Optional.empty());

		given(practicas.save(any())).willAnswer(invocacion -> {
			OfertaPractica fila = invocacion.getArgument(0);
			if (fila.getId() == null) {
				ReflectionTestUtils.setField(fila, "id", proximoId++);
				filas.add(fila);
			}
			return fila;
		});
		given(practicas.findAllByOrganizationIdAndOfertaIdOrderByIdAsc(ORG, OFERTA_ID))
				.willAnswer(invocacion -> List.copyOf(filas));
		given(practicas.findAllByOrganizationIdAndOfertaIdAndActiveOrderByIdAsc(ORG, OFERTA_ID, true))
				.willAnswer(invocacion -> filas.stream().filter(OfertaPractica::isOperable).toList());
	}

	@Test
	@DisplayName("el diff: lo que sale se da de baja, lo que sigue no se toca, lo que entra se crea")
	void reemplazo_hace_el_diff() {
		OfertaPractica a = existente(PRACTICA_A, true);
		OfertaPractica b = existente(PRACTICA_B, false);
		long versionDeB = b.getVersion();

		PracticasDeOfertaView vista = service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_B, PRACTICA_C), PRACTICA_C, VERSION);

		assertThat(a.isOperable()).isFalse();
		assertThat(a.isPrincipal()).as("una fila dada de baja deja de ser principal").isFalse();
		assertThat(a.getDeactivationReason()).isEqualTo(OfertaPracticaService.MOTIVO_REEMPLAZO);
		assertThat(b.isOperable()).isTrue();
		assertThat(b.getVersion()).isEqualTo(versionDeB);
		assertThat(vista.practicaPrincipalId()).isEqualTo(PRACTICA_C);
		assertThat(vista.practicas()).extracting(PracticasDeOfertaView.PracticaDeOfertaView::practicaId)
				.containsExactly(PRACTICA_A, PRACTICA_B, PRACTICA_C);
		assertThat(vista.practicas()).filteredOn(p -> p.estado().equals("ACTIVO"))
				.extracting(PracticasDeOfertaView.PracticaDeOfertaView::practicaId)
				.containsExactly(PRACTICA_B, PRACTICA_C);
	}

	@Test
	@DisplayName("principal a una practica NUEVA: la baja de la anterior se vuelca antes del INSERT")
	void la_baja_se_vuelca_antes_del_alta() {
		OfertaPractica a = existente(PRACTICA_A, true);

		service.reemplazar(admin, ORG, SEDE, OFERTA_ID, List.of(PRACTICA_A, PRACTICA_C),
				PRACTICA_C, VERSION);

		// Hibernate ejecuta los INSERT antes que los UPDATE: sin este flush intermedio el INSERT
		// de C con principal=1 choca contra uk_oferta_practica_principal mientras A sigue marcada.
		InOrder orden = inOrder(practicas);
		orden.verify(practicas).save(a);
		orden.verify(practicas).flush();
		orden.verify(practicas).save(argThat(fila ->
				fila != a && fila.getPracticaId() == PRACTICA_C && fila.isPrincipal()));
		assertThat(a.isOperable()).isTrue();
		assertThat(a.isPrincipal()).isFalse();
	}

	@Test
	@DisplayName("principal a una practica que YA estaba: desmarcar, volcar, marcar")
	void cambio_de_principal_entre_existentes() {
		OfertaPractica a = existente(PRACTICA_A, true);
		OfertaPractica b = existente(PRACTICA_B, false);

		PracticasDeOfertaView vista = service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_A, PRACTICA_B), PRACTICA_B, VERSION);

		InOrder orden = inOrder(practicas);
		orden.verify(practicas).save(a);
		orden.verify(practicas).flush();
		orden.verify(practicas).save(b);
		assertThat(a.isPrincipal()).isFalse();
		assertThat(b.isPrincipal()).isTrue();
		assertThat(vista.practicaPrincipalId()).isEqualTo(PRACTICA_B);

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		then(auditTrail).should(atLeastOnce()).record(auditoria.capture());
		assertThat(auditoria.getAllValues()).extracting(AuditEntry::eventType)
				.containsExactly(AuditEvents.OFERTA_PRACTICA_PRINCIPAL_CHANGED);
	}

	@Test
	@DisplayName("sin cambios no escribe, no audita y no vuelca nada")
	void reemplazo_identico_no_escribe() {
		existente(PRACTICA_A, true);

		service.reemplazar(admin, ORG, SEDE, OFERTA_ID, List.of(PRACTICA_A), PRACTICA_A, VERSION);

		then(practicas).should(never()).save(any());
		then(practicas).should(never()).flush();
		then(auditTrail).should(never()).record(any());
	}

	@Test
	@DisplayName("la principal tiene que ser coherente con la lista: 400 y nada escrito")
	void principal_incoherente() {
		assertThatThrownBy(() -> service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_A), null, VERSION))
				.isInstanceOf(PracticaPrincipalInvalidaException.class);
		assertThatThrownBy(() -> service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_A), PRACTICA_B, VERSION))
				.isInstanceOf(PracticaPrincipalInvalidaException.class);
		assertThatThrownBy(() -> service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(), PRACTICA_A, VERSION))
				.isInstanceOf(PracticaPrincipalInvalidaException.class);
		then(practicas).should(never()).save(any());
	}

	@Test
	@DisplayName("lista vacia con principal null es legitima: deja la oferta sin practicas")
	void lista_vacia_quita_todas() {
		existente(PRACTICA_A, true);

		PracticasDeOfertaView vista = service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(), null, VERSION);

		assertThat(vista.practicaPrincipalId()).isNull();
		assertThat(vista.practicas()).allMatch(p -> p.estado().equals("INACTIVO"));
	}

	@Test
	@DisplayName("una practica nueva ajena es 404 y una no vigente 409, sin escribir nada")
	void practica_que_entra_se_valida() {
		assertThatThrownBy(() -> service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_A, PRACTICA_AJENA), PRACTICA_A, VERSION))
				.isInstanceOfSatisfying(PracticaNoElegibleException.class, e ->
						assertThat(e.getMotivo()).isEqualTo(PracticaNoElegibleException.Motivo.INEXISTENTE));

		given(catalogo.findPractica(eq(ORG), eq(PRACTICA_C), any()))
				.willReturn(Optional.of(practica(PRACTICA_C, false)));
		assertThatThrownBy(() -> service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_C), PRACTICA_C, VERSION))
				.isInstanceOfSatisfying(PracticaNoElegibleException.class, e ->
						assertThat(e.getMotivo()).isEqualTo(PracticaNoElegibleException.Motivo.NO_VIGENTE));

		then(practicas).should(never()).save(any());
	}

	@Test
	@DisplayName("una practica que ya estaba y se dio de baja en el catalogo se conserva")
	void la_que_ya_estaba_no_se_revalida() {
		existente(PRACTICA_A, true);
		given(catalogo.findPractica(eq(ORG), eq(PRACTICA_A), any()))
				.willReturn(Optional.of(practica(PRACTICA_A, false)));

		PracticasDeOfertaView vista = service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_A, PRACTICA_B), PRACTICA_A, VERSION);

		assertThat(vista.practicaPrincipalId()).isEqualTo(PRACTICA_A);
		assertThat(vista.practicas().get(0).vigenteEnCatalogo()).isFalse();
	}

	@Test
	@DisplayName("version vieja: 409 por la carga con force-increment, sin escribir")
	void version_vieja() {
		assertThatThrownBy(() -> service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_A), PRACTICA_A, VERSION - 1))
				.isInstanceOf(OptimisticLockingFailureException.class);

		then(ofertas).should().findWithLockByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE);
		then(practicas).should(never()).save(any());
	}

	@Test
	@DisplayName("manda la version leida CON lock: si otro commiteo mientras se esperaba, 409 aunque "
			+ "la foto de REPEATABLE READ diga la vieja")
	void la_version_bloqueada_manda() {
		given(ofertas.bloquearParaConfigurar(OFERTA_ID, ORG, SEDE)).willReturn(Optional.of(VERSION + 1));

		assertThatThrownBy(() -> service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_A), PRACTICA_A, VERSION))
				.isInstanceOf(OptimisticLockingFailureException.class);

		InOrder orden = inOrder(ofertas);
		orden.verify(ofertas).bloquearParaConfigurar(OFERTA_ID, ORG, SEDE);
		orden.verify(ofertas).findWithLockByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE);
		then(practicas).should(never()).save(any());
	}

	@Test
	@DisplayName("la respuesta del reemplazo trae leida+1 y la lectura la vigente")
	void version_devuelta() {
		PracticasDeOfertaView tras = service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_A), PRACTICA_A, VERSION);
		PracticasDeOfertaView leida = service.leer(admin, ORG, SEDE, OFERTA_ID);

		assertThat(tras.ofertaVersion()).isEqualTo(VERSION + 1);
		assertThat(leida.ofertaVersion()).isEqualTo(VERSION);
		// Una sola carga con force-increment: la del reemplazo. Leer no mueve la version.
		then(ofertas).should(org.mockito.Mockito.times(1)).findWithLockByIdAndOrganizationIdAndConsultorioId(
				anyLong(), eq(ORG), anyLong());
	}

	@Test
	@DisplayName("sin consultorio:manage no se toca nada")
	void sin_permiso() {
		org.mockito.BDDMockito.willThrow(new org.springframework.security.access.AccessDeniedException("no"))
				.given(permissionGuard).requirePermission(any());

		assertThatThrownBy(() -> service.reemplazar(admin, ORG, SEDE, OFERTA_ID,
				List.of(PRACTICA_A), PRACTICA_A, VERSION))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		then(practicas).should(never()).save(any());
		then(practicas).should(never())
				.findAllByOrganizationIdAndOfertaIdAndActiveOrderByIdAsc(anyLong(), anyLong(), anyBoolean());
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private OfertaPractica existente(long practicaId, boolean principal) {
		OfertaPractica fila = new OfertaPractica(ORG, SEDE, OFERTA_ID, practicaId, principal);
		ReflectionTestUtils.setField(fila, "id", proximoId++);
		filas.add(fila);
		return fila;
	}

	private static OfertaServicioConsultorio oferta() {
		OfertaServicioConsultorio oferta = new OfertaServicioConsultorio(
				ORG, SEDE, 1L, "Kinesiologia", null, Modalidad.INDIVIDUAL, 45, 1,
				new BigDecimal("18000.00"), "ARS", new EsquemaCobro("SESION_SUELTA"),
				false, false, true, true, true,
				LocalDate.now().minusDays(1), null);
		ReflectionTestUtils.setField(oferta, "id", OFERTA_ID);
		ReflectionTestUtils.setField(oferta, "version", VERSION);
		return oferta;
	}

	private static CatalogoSnapshot practica(long id, boolean vigente) {
		return new CatalogoSnapshot(id, null, "P-" + id, "Practica " + id,
				Instant.parse("2020-01-01T00:00:00Z"), null, vigente, vigente, 1L, null, 0L);
	}
}
