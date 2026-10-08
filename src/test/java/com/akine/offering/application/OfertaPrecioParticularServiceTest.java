package com.akine.offering.application;

import com.akine.offering.domain.EsquemaCobro;
import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.OfertaPrecioParticular;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.exception.OfertaNotAccessibleException;
import com.akine.offering.domain.exception.PrecioParticularInactivoException;
import com.akine.offering.domain.exception.PrecioParticularSolapadoException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaPrecioParticularRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B-3 (RF-M16-009): precio particular por vigencia, sin base. La concurrencia contra MySQL la
 * cubre {@code CoberturaPorOfertaIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Precio particular por vigencia (B-3)")
class OfertaPrecioParticularServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 3L;
	private static final long OFERTA_ID = 34L;
	private static final LocalDate ENERO = LocalDate.of(2027, 1, 1);
	private static final LocalDate JUNIO_30 = LocalDate.of(2027, 6, 30);
	private static final LocalDate JULIO = LocalDate.of(2027, 7, 1);
	private static final LocalDate MARZO = LocalDate.of(2027, 3, 15);

	@Mock private OfertaRepositoryPort ofertas;
	@Mock private OfertaPrecioParticularRepositoryPort precios;
	@Mock private ConsultorioDirectory consultorioDirectory;
	@Mock private AccountContextDirectory accountContextDirectory;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private OfertaPrecioParticularService service;
	private final OperatingActor admin = new OperatingActor(170L, false, ORG, SEDE);
	private final List<OfertaPrecioParticular> activos = new ArrayList<>();

	@BeforeEach
	void setUp() {
		service = new OfertaPrecioParticularService(ofertas, precios, consultorioDirectory,
				accountContextDirectory, permissionGuard, auditTrail);
		given(consultorioDirectory.find(ORG, SEDE))
				.willReturn(Optional.of(new ConsultorioSnapshot(SEDE, ORG, "Sede", "UTC", true)));
		given(ofertas.bloquearParaConfigurar(OFERTA_ID, ORG, SEDE)).willReturn(Optional.of(4L));
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(oferta()));
		given(precios.findAllByOrganizationIdAndOfertaIdAndActiveOrderByVigenciaDesdeDescIdDesc(
				ORG, OFERTA_ID, true)).willAnswer(i -> List.copyOf(activos));
		given(precios.saveAndFlush(any())).willAnswer(i -> {
			OfertaPrecioParticular p = i.getArgument(0);
			if (p.getId() == null) {
				ReflectionTestUtils.setField(p, "id", 100L + activos.size());
			}
			return p;
		});
	}

	@Test
	@DisplayName("dos precios que se pisan: 409 y no se escribe; consecutivos conviven")
	void solapamiento() {
		activos.add(precio(1L, ENERO, JUNIO_30));

		assertThatThrownBy(() -> service.crear(admin, ORG, SEDE, OFERTA_ID,
				new BigDecimal("9000.00"), null, MARZO, null))
				.isInstanceOf(PrecioParticularSolapadoException.class);
		verify(precios, never()).saveAndFlush(any());

		OfertaPrecioParticularView julio = service.crear(admin, ORG, SEDE, OFERTA_ID,
				new BigDecimal("9000.00"), null, JULIO, null);
		assertThat(julio.moneda()).isEqualTo("ARS");
	}

	@Test
	@DisplayName("EL ORDEN ES LA GARANTIA: lock de la oferta, despues leer el conjunto")
	void lock_antes_de_leer() {
		service.crear(admin, ORG, SEDE, OFERTA_ID, new BigDecimal("9000.00"), "ARS", ENERO, null);

		InOrder orden = inOrder(ofertas, precios);
		orden.verify(ofertas).bloquearParaConfigurar(OFERTA_ID, ORG, SEDE);
		orden.verify(precios).findAllByOrganizationIdAndOfertaIdAndActiveOrderByVigenciaDesdeDescIdDesc(
				ORG, OFERTA_ID, true);
		orden.verify(precios).saveAndFlush(any());
	}

	@Test
	@DisplayName("extender la vigencia sobre otro precio tambien es 409")
	void extender_solapa() {
		OfertaPrecioParticular primero = precio(1L, ENERO, JUNIO_30);
		activos.add(primero);
		activos.add(precio(2L, JULIO, null));
		given(precios.findByIdAndOrganizationIdAndOfertaId(1L, ORG, OFERTA_ID))
				.willReturn(Optional.of(primero));

		assertThatThrownBy(() -> service.cambiarFin(admin, ORG, SEDE, OFERTA_ID, 1L, null, 0L))
				.isInstanceOf(PrecioParticularSolapadoException.class);
	}

	@Test
	@DisplayName("un precio dado de baja no admite otra baja")
	void baja_de_inactivo() {
		OfertaPrecioParticular p = precio(1L, ENERO, null);
		p.deactivate(Instant.now(), "carga erronea");
		given(precios.findByIdAndOrganizationIdAndOfertaId(1L, ORG, OFERTA_ID))
				.willReturn(Optional.of(p));

		assertThatThrownBy(() -> service.darDeBaja(admin, ORG, SEDE, OFERTA_ID, 1L, "otra vez"))
				.isInstanceOf(PrecioParticularInactivoException.class);
	}

	@Test
	@DisplayName("cerrar la vigencia: no choca contra si mismo y se audita el periodo anterior")
	void cerrar_vigencia() {
		OfertaPrecioParticular abierto = precio(1L, ENERO, null);
		activos.add(abierto);
		given(precios.findByIdAndOrganizationIdAndOfertaId(1L, ORG, OFERTA_ID))
				.willReturn(Optional.of(abierto));

		OfertaPrecioParticularView cerrado =
				service.cambiarFin(admin, ORG, SEDE, OFERTA_ID, 1L, JUNIO_30, 0L);

		assertThat(cerrado.vigenciaHasta()).isEqualTo(JUNIO_30);
		ArgumentCaptor<AuditEntry> auditada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditada.capture());
		assertThat(auditada.getValue().eventType())
				.isEqualTo(AuditEvents.OFERTA_PRECIO_PARTICULAR_UPDATED);
		assertThat(auditada.getValue().details()).containsKey("periodoAnterior");
	}

	@Test
	@DisplayName("cambiar el fin con una version vieja es 409 y no escribe")
	void cambiar_fin_con_version_vieja() {
		given(precios.findByIdAndOrganizationIdAndOfertaId(1L, ORG, OFERTA_ID))
				.willReturn(Optional.of(precio(1L, ENERO, null)));

		assertThatThrownBy(() -> service.cambiarFin(admin, ORG, SEDE, OFERTA_ID, 1L, JUNIO_30, 5L))
				.isInstanceOf(OptimisticLockingFailureException.class);
		verify(precios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("la baja exige motivo antes de tomar el lock, y lo guarda recortado")
	void baja_con_motivo() {
		assertThatThrownBy(() -> service.darDeBaja(admin, ORG, SEDE, OFERTA_ID, 1L, "  "))
				.isInstanceOf(IllegalArgumentException.class);
		verify(ofertas, never()).bloquearParaConfigurar(OFERTA_ID, ORG, SEDE);

		given(precios.findByIdAndOrganizationIdAndOfertaId(1L, ORG, OFERTA_ID))
				.willReturn(Optional.of(precio(1L, ENERO, null)));

		OfertaPrecioParticularView baja =
				service.darDeBaja(admin, ORG, SEDE, OFERTA_ID, 1L, "  carga erronea ");

		assertThat(baja.deactivationReason()).isEqualTo("carga erronea");
		assertThat(baja.deletedAt()).isNotNull();
		ArgumentCaptor<AuditEntry> auditada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditada.capture());
		assertThat(auditada.getValue().reason()).isEqualTo("carga erronea");
	}

	@Test
	@DisplayName("oferta de otra sede: 404 antes de escribir")
	void oferta_ajena() {
		given(ofertas.bloquearParaConfigurar(OFERTA_ID, ORG, SEDE)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.crear(admin, ORG, SEDE, OFERTA_ID,
				new BigDecimal("9000.00"), null, ENERO, null))
				.isInstanceOf(OfertaNotAccessibleException.class);
	}

	@Test
	@DisplayName("el precio del dia: el particular vigente manda; fuera de su vigencia, el de lista")
	void precio_del_dia() {
		activos.add(precio(1L, ENERO, JUNIO_30));

		Optional<PrecioDeOferta> marzo = service.precioVigenteEl(ORG, SEDE, OFERTA_ID, MARZO);
		Optional<PrecioDeOferta> julio = service.precioVigenteEl(ORG, SEDE, OFERTA_ID, JULIO);

		assertThat(marzo).get().extracting(PrecioDeOferta::precioBase)
				.isEqualTo(new BigDecimal("7000.00"));
		assertThat(julio).get().extracting(PrecioDeOferta::precioBase)
				.isEqualTo(new BigDecimal("18000.00"));
		assertThat(julio.get().admiteObraSocial()).isTrue();
	}

	@Test
	@DisplayName("una moneda que no es ISO 4217 es 400")
	void moneda_invalida() {
		assertThatThrownBy(() -> service.crear(admin, ORG, SEDE, OFERTA_ID,
				new BigDecimal("9000.00"), "XYZ", ENERO, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// =================================================================================

	private static OfertaPrecioParticular precio(long id, LocalDate desde, LocalDate hasta) {
		OfertaPrecioParticular p = new OfertaPrecioParticular(
				ORG, SEDE, OFERTA_ID, new BigDecimal("7000.00"), "ARS", desde, hasta);
		ReflectionTestUtils.setField(p, "id", id);
		return p;
	}

	private static OfertaServicioConsultorio oferta() {
		OfertaServicioConsultorio oferta = new OfertaServicioConsultorio(
				ORG, SEDE, 1L, "Kinesiologia", null, Modalidad.INDIVIDUAL, 45, 1,
				new BigDecimal("18000.00"), "ARS", new EsquemaCobro("SESION_SUELTA"),
				true, false, true, true, true, LocalDate.now().minusDays(1), null);
		ReflectionTestUtils.setField(oferta, "id", OFERTA_ID);
		return oferta;
	}
}
