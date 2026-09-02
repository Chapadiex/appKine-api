package com.akine.contracting.application;

import com.akine.contracting.application.ArancelCommands.ArancelAltaCommand;
import com.akine.contracting.application.ArancelCommands.ArancelEdicionCommand;
import com.akine.contracting.domain.Convenio;
import com.akine.contracting.domain.ConvenioArancel;
import com.akine.contracting.domain.ConvenioLock;
import com.akine.contracting.domain.ModalidadConvenio;
import com.akine.contracting.domain.exception.ArancelNotAccessibleException;
import com.akine.contracting.domain.exception.ArancelSolapadoException;
import com.akine.contracting.domain.exception.ArancelYaInactivoException;
import com.akine.contracting.domain.exception.ConvenioNotAccessibleException;
import com.akine.contracting.domain.exception.ConvenioYaInactivoException;
import com.akine.contracting.domain.exception.PracticaNoAccesibleException;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioArancelRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioLockRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioRepositoryPort;
import com.akine.contracting.spi.MotivoSinArancel;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Los invariantes de los aranceles y la resolucion, sin base de datos.
 *
 * <p>Lo mismo que {@code ConvenioServiceTest}: prueba la REGLA, no la concurrencia. Que dos altas
 * simultaneas no se pisen lo decide InnoDB, y eso lo prueba {@code ConvenioConcurrenteIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ArancelService")
class ArancelServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 7L;
	private static final long SEDE_ID = 20L;
	private static final long FINANCIADOR_ID = 31L;
	private static final long PLAN_ID = 88L;
	private static final long CONVENIO_ID = 140L;
	private static final long ARANCEL_ID = 901L;
	private static final long PRACTICA_ID = 412L;
	private static final LocalDate ENERO = LocalDate.of(2026, 1, 1);
	private static final LocalDate MARZO = LocalDate.of(2026, 3, 15);
	private static final LocalDate JUNIO_30 = LocalDate.of(2026, 6, 30);
	private static final LocalDate JULIO = LocalDate.of(2026, 7, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2026, 12, 31);

	@Mock private ConvenioRepositoryPort convenios;
	@Mock private ConvenioArancelRepositoryPort aranceles;
	@Mock private ConvenioLockRepositoryPort locks;
	@Mock private ConvenioLockIniciador lockIniciador;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private CatalogoDirectory catalogo;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private ArancelService service;

	private final OperatingActor delMostrador =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new ArancelService(convenios, aranceles, locks, lockIniciador, consultorios,
				catalogo, permissionGuard, auditTrail);

		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(new ConsultorioSnapshot(
				SEDE_ID, ORG_ID, "Sede Centro", "America/Argentina/Cordoba", true)));
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(locks.lockByScope(anyLong(), anyLong()))
				.willReturn(Optional.of(Mockito.mock(ConvenioLock.class)));
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID))
				.willReturn(Optional.of(convenio(ENERO, DICIEMBRE)));
		given(catalogo.findPractica(anyLong(), anyLong(), any()))
				.willReturn(Optional.of(practica()));
		given(aranceles.findActivosPorPractica(anyLong(), anyLong(), anyLong()))
				.willReturn(List.of());
		given(aranceles.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(aranceles.save(any())).willAnswer(i -> conId(i.getArgument(0)));
	}

	// =================================================================================
	// La regla de la etapa, un nivel mas abajo
	// =================================================================================

	@Test
	@DisplayName("dos aranceles de la misma practica que se pisan: 409 con el que choca")
	void solapamiento_rechazado() {
		given(aranceles.findActivosPorPractica(ORG_ID, CONVENIO_ID, PRACTICA_ID))
				.willReturn(List.of(arancel(999L, ENERO, DICIEMBRE)));

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, CONVENIO_ID,
				alta(PRACTICA_ID, MARZO, DICIEMBRE)))
				.isInstanceOf(ArancelSolapadoException.class)
				.satisfies(error -> assertThat(
						((ArancelSolapadoException) error).getArancelExistenteId()).isEqualTo(999L));

		verify(aranceles, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("dos aranceles CONSECUTIVOS de la misma practica conviven: es como se sube un "
			+ "precio")
	void consecutivos_conviven() {
		given(aranceles.findActivosPorPractica(ORG_ID, CONVENIO_ID, PRACTICA_ID))
				.willReturn(List.of(arancel(999L, ENERO, JUNIO_30)));

		assertThatCode(() -> service.crear(delMostrador, SEDE_ID, CONVENIO_ID,
				alta(PRACTICA_ID, JULIO, DICIEMBRE)))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("EL ORDEN ES LA GARANTIA tambien aca: asegurar, bloquear, leer")
	void el_orden_de_la_concurrencia() {
		service.crear(delMostrador, SEDE_ID, CONVENIO_ID, alta(PRACTICA_ID, ENERO, DICIEMBRE));

		InOrder orden = inOrder(lockIniciador, locks, aranceles);
		orden.verify(lockIniciador).asegurar(ORG_ID, SEDE_ID);
		orden.verify(locks).lockByScope(ORG_ID, SEDE_ID);
		orden.verify(aranceles).findActivosPorPractica(ORG_ID, CONVENIO_ID, PRACTICA_ID);
		orden.verify(aranceles).saveAndFlush(any());
	}

	@Test
	@DisplayName("otra PRACTICA no compite por el periodo")
	void otra_practica_no_compite() {
		service.crear(delMostrador, SEDE_ID, CONVENIO_ID, alta(PRACTICA_ID, ENERO, DICIEMBRE));

		verify(aranceles).findActivosPorPractica(ORG_ID, CONVENIO_ID, PRACTICA_ID);
	}

	// =================================================================================
	// Invariantes economicas y de vigencia
	// =================================================================================

	@Test
	@DisplayName("la moneda la hereda del convenio, no del cliente")
	void hereda_la_moneda() {
		ArancelView creado = service.crear(
				delMostrador, SEDE_ID, CONVENIO_ID, alta(PRACTICA_ID, ENERO, DICIEMBRE));

		assertThat(creado.moneda()).isEqualTo("ARS");
	}

	@Test
	@DisplayName("un arancel que sobresale de la vigencia de su convenio es dato muerto: 400")
	void fuera_de_la_vigencia_del_convenio() {
		// Fuera de la ventana del convenio nunca podria resolver, porque la resolucion exige
		// primero un convenio aplicable. Aceptarlo dejaria en la grilla filas que prometen un
		// precio que el motor no va a usar nunca.
		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, CONVENIO_ID,
				alta(PRACTICA_ID, ENERO, DICIEMBRE.plusDays(1))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("contenida en la del convenio");
	}

	@Test
	@DisplayName("los importes que no cuadran se rechazan antes de tocar la base")
	void importes_que_no_cuadran() {
		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, CONVENIO_ID,
				new ArancelAltaCommand(PRACTICA_ID, new BigDecimal("12000.00"),
						new BigDecimal("9600.00"), new BigDecimal("2500.00"), ENERO, DICIEMBRE)))
				.isInstanceOf(IllegalArgumentException.class);

		verify(aranceles, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("los importes entran en la auditoria: §37 pide auditoria reforzada")
	void los_importes_se_auditan() {
		service.crear(delMostrador, SEDE_ID, CONVENIO_ID, alta(PRACTICA_ID, ENERO, DICIEMBRE));

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().details())
				.containsEntry("importeTotal", "12000.00 ARS")
				.containsEntry("coseguro", "2400.00")
				.containsKey("vigencia");
	}

	@Test
	@DisplayName("la edicion audita el antes y el despues de cada importe que cambia")
	void la_edicion_audita_el_cambio() {
		given(aranceles.findByIdAndScope(ARANCEL_ID, ORG_ID, CONVENIO_ID))
				.willReturn(Optional.of(arancel(ARANCEL_ID, ENERO, DICIEMBRE)));

		service.editar(delMostrador, SEDE_ID, CONVENIO_ID, ARANCEL_ID,
				new ArancelEdicionCommand(new BigDecimal("13000.00"), new BigDecimal("13000.00"),
						BigDecimal.ZERO, null, null, 0L));

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().details())
				.containsEntry("importeTotal", "12000.00 -> 13000.00")
				.containsEntry("coseguro", "2400.00 -> 0");
	}

	// =================================================================================
	// Autorizacion, tenant y ciclo de vida
	// =================================================================================

	@Test
	@DisplayName("una practica de otro tenant es 404: la FK compara ids, no alcances")
	void practica_ajena_404() {
		given(catalogo.findPractica(anyLong(), anyLong(), any())).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, CONVENIO_ID,
				alta(PRACTICA_ID, ENERO, DICIEMBRE)))
				.isInstanceOf(PracticaNoAccesibleException.class);
	}

	@Test
	@DisplayName("un convenio de otra sede es 404")
	void convenio_de_otra_sede_404() {
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, CONVENIO_ID,
				alta(PRACTICA_ID, ENERO, DICIEMBRE)))
				.isInstanceOf(ConvenioNotAccessibleException.class);
	}

	@Test
	@DisplayName("un convenio dado de baja no admite aranceles nuevos")
	void convenio_inactivo() {
		Convenio dadoDeBaja = convenioConId(ENERO, DICIEMBRE);
		dadoDeBaja.deactivate(Instant.now(), "Renegociado");
		given(convenios.findByIdAndScope(CONVENIO_ID, ORG_ID, SEDE_ID))
				.willReturn(Optional.of(dadoDeBaja));

		assertThatThrownBy(() -> service.crear(delMostrador, SEDE_ID, CONVENIO_ID,
				alta(PRACTICA_ID, ENERO, DICIEMBRE)))
				.isInstanceOf(ConvenioYaInactivoException.class);
	}

	@Test
	@DisplayName("un arancel de otro convenio es 404")
	void arancel_de_otro_convenio_404() {
		given(aranceles.findByIdAndScope(ARANCEL_ID, ORG_ID, CONVENIO_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.darDeBaja(
				delMostrador, SEDE_ID, CONVENIO_ID, ARANCEL_ID, "Renegociado"))
				.isInstanceOf(ArancelNotAccessibleException.class);
	}

	@Test
	@DisplayName("un arancel ya dado de baja no se edita ni se vuelve a dar de baja")
	void arancel_ya_inactivo() {
		ConvenioArancel dadoDeBaja = arancel(ARANCEL_ID, ENERO, DICIEMBRE);
		dadoDeBaja.deactivate(Instant.now(), "Renegociado");
		given(aranceles.findByIdAndScope(ARANCEL_ID, ORG_ID, CONVENIO_ID))
				.willReturn(Optional.of(dadoDeBaja));

		assertThatThrownBy(() -> service.darDeBaja(
				delMostrador, SEDE_ID, CONVENIO_ID, ARANCEL_ID, "otra vez"))
				.isInstanceOf(ArancelYaInactivoException.class);
	}

	@Test
	@DisplayName("la baja exige motivo y deja el arancel INACTIVO")
	void la_baja_exige_motivo() {
		given(aranceles.findByIdAndScope(ARANCEL_ID, ORG_ID, CONVENIO_ID))
				.willReturn(Optional.of(arancel(ARANCEL_ID, ENERO, DICIEMBRE)));

		assertThatThrownBy(() -> service.darDeBaja(
				delMostrador, SEDE_ID, CONVENIO_ID, ARANCEL_ID, " "))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(service.darDeBaja(delMostrador, SEDE_ID, CONVENIO_ID, ARANCEL_ID, "Renegociado")
				.estado()).isEqualTo("INACTIVO");
	}

	@Test
	@DisplayName("una version vieja es 409")
	void version_vieja() {
		ConvenioArancel propio = arancel(ARANCEL_ID, ENERO, DICIEMBRE);
		ReflectionTestUtils.setField(propio, "version", 5L);
		given(aranceles.findByIdAndScope(ARANCEL_ID, ORG_ID, CONVENIO_ID))
				.willReturn(Optional.of(propio));

		assertThatThrownBy(() -> service.editar(delMostrador, SEDE_ID, CONVENIO_ID, ARANCEL_ID,
				new ArancelEdicionCommand(null, null, null, null, JUNIO_30, 0L)))
				.isInstanceOf(OptimisticLockingFailureException.class);
	}

	@Test
	@DisplayName("el listado filtra por ciclo de vida y por defecto trae los activos")
	void listado_filtra() {
		ConvenioArancel activo = arancel(1L, ENERO, JUNIO_30);
		ConvenioArancel baja = arancel(2L, JULIO, DICIEMBRE);
		baja.deactivate(Instant.now(), "Renegociado");
		given(aranceles.findAllByConvenio(ORG_ID, CONVENIO_ID)).willReturn(List.of(activo, baja));

		assertThat(service.listar(delMostrador, SEDE_ID, CONVENIO_ID, null, MARZO)).hasSize(1);
		assertThat(service.listar(delMostrador, SEDE_ID, CONVENIO_ID, EstadoFiltro.TODOS, MARZO))
				.hasSize(2);
	}

	// =================================================================================
	// Resolucion
	// =================================================================================

	@Test
	@DisplayName("resolver devuelve el arancel con su derivacion cuando hay convenio y arancel")
	void resolver_ok() {
		given(convenios.findActivosPorAlcance(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID))
				.willReturn(List.of(convenioConId(ENERO, DICIEMBRE)));
		given(aranceles.findActivosPorPractica(ORG_ID, CONVENIO_ID, PRACTICA_ID))
				.willReturn(List.of(arancel(ARANCEL_ID, ENERO, DICIEMBRE)));

		ResolucionDeArancel resolucion = service.resolver(
				delMostrador, SEDE_ID, FINANCIADOR_ID, PLAN_ID, PRACTICA_ID, MARZO);

		assertThat(resolucion.estaResuelta()).isTrue();
		assertThat(resolucion.arancel().importeTotal()).isEqualByComparingTo("12000.00");
		assertThat(resolucion.arancel().convenioId()).isEqualTo(CONVENIO_ID);
	}

	@Test
	@DisplayName("sin convenio NO se leen aranceles: la consulta de mas no se hace")
	void sin_convenio_no_se_leen_aranceles() {
		// En el caso mas frecuente —paciente particular— esa segunda consulta no se usaria para
		// nada.
		given(convenios.findActivosPorAlcance(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID))
				.willReturn(List.of());

		ResolucionDeArancel resolucion = service.resolver(
				delMostrador, SEDE_ID, FINANCIADOR_ID, PLAN_ID, PRACTICA_ID, MARZO);

		assertThat(resolucion.motivo()).isEqualTo(MotivoSinArancel.SIN_CONVENIO_VIGENTE);
		verify(aranceles, never()).findActivosPorPractica(anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("sin fecha se resuelve contra hoy")
	void sin_fecha_resuelve_hoy() {
		given(convenios.findActivosPorAlcance(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID))
				.willReturn(List.of());

		assertThat(service.resolver(delMostrador, SEDE_ID, FINANCIADOR_ID, PLAN_ID, PRACTICA_ID,
				null).estaResuelta()).isFalse();
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ArancelAltaCommand alta(long practicaId, LocalDate desde, LocalDate hasta) {
		return new ArancelAltaCommand(practicaId, new BigDecimal("12000.00"),
				new BigDecimal("9600.00"), new BigDecimal("2400.00"), desde, hasta);
	}

	private static Convenio convenio(LocalDate desde, LocalDate hasta) {
		return new Convenio(ORG_ID, SEDE_ID, FINANCIADOR_ID, PLAN_ID, "OSDE-210", "OSDE 210",
				ModalidadConvenio.POR_PRESTACION, desde, hasta, "ARS",
				false, false, true, null, null, null);
	}

	private static Convenio convenioConId(LocalDate desde, LocalDate hasta) {
		Convenio convenio = convenio(desde, hasta);
		ReflectionTestUtils.setField(convenio, "id", CONVENIO_ID);
		return convenio;
	}

	private static ConvenioArancel arancel(long id, LocalDate desde, LocalDate hasta) {
		ConvenioArancel arancel = new ConvenioArancel(ORG_ID, SEDE_ID, CONVENIO_ID, PRACTICA_ID,
				new BigDecimal("12000.00"), new BigDecimal("9600.00"), new BigDecimal("2400.00"),
				"ARS", desde, hasta);
		ReflectionTestUtils.setField(arancel, "id", id);
		return arancel;
	}

	private static CatalogoSnapshot practica() {
		return new CatalogoSnapshot(PRACTICA_ID, ORG_ID, "KIN-01", "Kinesiologia",
				Instant.parse("2020-01-01T00:00:00Z"), null, true, true, 1L, null, 0L);
	}

	private static ConvenioArancel conId(ConvenioArancel arancel) {
		if (arancel.getId() == null) {
			ReflectionTestUtils.setField(arancel, "id", ARANCEL_ID);
		}
		return arancel;
	}
}
