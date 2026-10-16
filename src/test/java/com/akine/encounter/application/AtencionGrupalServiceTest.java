package com.akine.encounter.application;

import com.akine.clinical.spi.CasoDirectory;
import com.akine.clinical.spi.CasoSnapshot;
import com.akine.clinical.spi.DerivacionClinicaRegistry;
import com.akine.clinical.spi.DerivacionSnapshot;
import com.akine.clinical.spi.OrigenDeParticipacion;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.exception.CasoDeAtencionGrupalException;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.spi.ActorDeAtencion;
import com.akine.encounter.spi.AtencionDeParticipante;
import com.akine.encounter.spi.PedidoDeAtencion;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La apertura de la atencion de un participante desde su derivacion (AKINE-08.05). Lo que se
 * prueba es lo que decide {@code encounter}: el orden de los gates, que la Sesion nazca con el
 * Caso, la historia y la oferta de la DERIVACION y no del pedido, sin turno, del profesional que
 * la abre, y que el Caso no se pueda cambiar despues.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtencionGrupalServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 7L;
	private static final long CUENTA_ID = 91L;
	private static final long MEMBERSHIP_ID = 31L;
	private static final long CLASE_ID = 640L;
	private static final long ASISTENCIA_ID = 931L;
	private static final long PERSONA_ID = 77L;
	private static final long DERIVACION_ID = 88L;
	private static final long HC_ID = 301L;
	private static final long CASO_ID = 55L;
	private static final long OFERTA_ID = 42L;

	@Mock private SesionRepositoryPort sesiones;
	@Mock private DerivacionClinicaRegistry derivaciones;
	@Mock private CasoDirectory casos;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private ConsultorioMembershipDirectory memberships;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private AtencionGrupalService service;

	@BeforeEach
	void setUp() {
		service = new AtencionGrupalService(sesiones, derivaciones, casos, consultorios, memberships,
				permissionGuard, auditTrail);
		given(consultorios.find(ORG_ID, SEDE_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(SEDE_ID, ORG_ID, "Sede", "UTC", true)));
		given(memberships.findByAccount(ORG_ID, CUENTA_ID)).willReturn(List.of(
				new ConsultorioMembershipSnapshot(MEMBERSHIP_ID, CUENTA_ID, ORG_ID, SEDE_ID,
						"PROFESIONAL", "ACTIVA", Instant.EPOCH, null, true, true)));
		given(derivaciones.paraAtender(any(), eq(OrigenDeParticipacion.CLASE_PROGRAMADA),
				eq(ASISTENCIA_ID), eq(PERSONA_ID), any())).willReturn(derivacion());
		given(casos.find(ORG_ID, CASO_ID)).willReturn(Optional.of(
				new CasoSnapshot(CASO_ID, ORG_ID, HC_ID, 1, true)));
		given(sesiones.findVivaPorDerivacion(ORG_ID, DERIVACION_ID)).willReturn(Optional.empty());
		given(sesiones.saveAndFlush(any())).willAnswer(invocacion -> {
			Sesion sesion = invocacion.getArgument(0);
			ReflectionTestUtils.setField(sesion, "id", 501L);
			return sesion;
		});
	}

	@Test
	@DisplayName("nace con la historia, el Caso y la oferta de la derivacion, sin turno y del profesional que la abre")
	void nace_desde_la_derivacion() {
		AtencionDeParticipante atencion = service.iniciar(pedido("Indicacion sintetica"));

		ArgumentCaptor<Sesion> guardada = ArgumentCaptor.forClass(Sesion.class);
		verify(sesiones).saveAndFlush(guardada.capture());
		Sesion sesion = guardada.getValue();
		assertThat(sesion.getHistoriaClinicaId()).isEqualTo(HC_ID);
		assertThat(sesion.getCasoId()).isEqualTo(CASO_ID);
		assertThat(sesion.getOfertaId()).isEqualTo(OFERTA_ID);
		assertThat(sesion.getTurnoId()).isNull();
		assertThat(sesion.getDerivacionId()).isEqualTo(DERIVACION_ID);
		assertThat(sesion.getParticipacionId()).isEqualTo(ASISTENCIA_ID);
		assertThat(sesion.getClaseId()).isEqualTo(CLASE_ID);
		assertThat(sesion.getProfesionalMembershipId()).isEqualTo(MEMBERSHIP_ID);
		assertThat(atencion.yaExistia()).isFalse();
		assertThat(atencion.sesionId()).isEqualTo(501L);

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().eventType()).isEqualTo(AuditEvents.SESION_INICIADA);
		assertThat(entrada.getValue().details())
				.containsEntry("origen", "CLASE_PROGRAMADA")
				.containsEntry("derivacionId", String.valueOf(DERIVACION_ID));
	}

	@Test
	@DisplayName("la justificacion viaja a clinical tal como llego")
	void justificacion_a_clinical() {
		service.iniciar(pedido("Indicacion sintetica"));

		ArgumentCaptor<com.akine.clinical.spi.ActorDeDerivacion> actor =
				ArgumentCaptor.forClass(com.akine.clinical.spi.ActorDeDerivacion.class);
		verify(derivaciones).paraAtender(actor.capture(), any(), anyLong(), anyLong(), any());
		assertThat(actor.getValue().justificacion()).isEqualTo("Indicacion sintetica");
		assertThat(actor.getValue().consultorioId()).isEqualTo(SEDE_ID);
	}

	@Test
	@DisplayName("el doble inicio devuelve la existente con yaExistia, sin insertar ni auditar otra vez")
	void idempotente() {
		Sesion existente = Sesion.desdeParticipacion(ORG_ID, SEDE_ID, HC_ID, CASO_ID, OFERTA_ID,
				MEMBERSHIP_ID, Instant.EPOCH, CUENTA_ID, DERIVACION_ID, ASISTENCIA_ID, CLASE_ID);
		ReflectionTestUtils.setField(existente, "id", 500L);
		given(sesiones.findVivaPorDerivacion(ORG_ID, DERIVACION_ID)).willReturn(Optional.of(existente));

		AtencionDeParticipante atencion = service.iniciar(pedido(null));

		assertThat(atencion.yaExistia()).isTrue();
		assertThat(atencion.sesionId()).isEqualTo(500L);
		verify(sesiones, never()).saveAndFlush(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("el acceso clinico va antes de la idempotencia: sin el, ni se mira si existe")
	void acceso_clinico_primero() {
		given(derivaciones.paraAtender(any(), any(), anyLong(), anyLong(), any()))
				.willThrow(new org.springframework.security.access.AccessDeniedException("hc:write"));

		assertThatThrownBy(() -> service.iniciar(pedido(null)))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		verify(sesiones, never()).findVivaPorDerivacion(anyLong(), anyLong());
	}

	@Test
	@DisplayName("con el Caso de la derivacion cerrado es 409 caso-clinico-cerrado y no se escribe nada")
	void caso_cerrado() {
		given(casos.find(ORG_ID, CASO_ID)).willReturn(Optional.of(
				new CasoSnapshot(CASO_ID, ORG_ID, HC_ID, 1, false)));

		assertThatThrownBy(() -> service.iniciar(pedido(null)))
				.isInstanceOf(CasoNoAsignableException.class)
				.extracting(e -> ((CasoNoAsignableException) e).tipoDeProblema().value())
				.asString().endsWith("caso-clinico-cerrado");
		verify(sesiones, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("el Caso de una atencion grupal no se cambia ni se quita; pedir el mismo pasa")
	void caso_fijo() {
		Sesion sesion = Sesion.desdeParticipacion(ORG_ID, SEDE_ID, HC_ID, CASO_ID, OFERTA_ID,
				MEMBERSHIP_ID, Instant.EPOCH, CUENTA_ID, DERIVACION_ID, ASISTENCIA_ID, CLASE_ID);

		sesion.asignarCaso(CASO_ID);
		assertThatThrownBy(() -> sesion.asignarCaso(CASO_ID + 1))
				.isInstanceOf(CasoDeAtencionGrupalException.class);
		assertThatThrownBy(() -> sesion.asignarCaso(null))
				.isInstanceOf(CasoDeAtencionGrupalException.class);
		assertThat(sesion.getCasoId()).isEqualTo(CASO_ID);
	}

	@Test
	@DisplayName("el panel lista sin contenido clinico y deja una sola fila de auditoria")
	void de_la_clase() {
		Sesion sesion = Sesion.desdeParticipacion(ORG_ID, SEDE_ID, HC_ID, CASO_ID, OFERTA_ID,
				MEMBERSHIP_ID, Instant.EPOCH, CUENTA_ID, DERIVACION_ID, ASISTENCIA_ID, CLASE_ID);
		ReflectionTestUtils.setField(sesion, "id", 500L);
		given(sesiones.findVivasDeLaClase(ORG_ID, SEDE_ID, CLASE_ID)).willReturn(List.of(sesion));

		List<AtencionDeParticipante> atenciones = service.deLaClase(actor(null), CLASE_ID);

		assertThat(atenciones).extracting(AtencionDeParticipante::participacionId)
				.containsExactly(ASISTENCIA_ID);
		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().eventType()).isEqualTo(AuditEvents.SESION_GRUPAL_CONSULTADA);
		verifyNoInteractions(derivaciones);
	}

	// =================================================================================

	private static ActorDeAtencion actor(String justificacion) {
		return new ActorDeAtencion(CUENTA_ID, false, ORG_ID, SEDE_ID, justificacion);
	}

	private static PedidoDeAtencion pedido(String justificacion) {
		return new PedidoDeAtencion(actor(justificacion), CLASE_ID, ASISTENCIA_ID, PERSONA_ID, null);
	}

	private static DerivacionSnapshot derivacion() {
		return new DerivacionSnapshot(DERIVACION_ID, ORG_ID, SEDE_ID,
				OrigenDeParticipacion.CLASE_PROGRAMADA, ASISTENCIA_ID, PERSONA_ID, HC_ID, CASO_ID,
				null, OFERTA_ID, true, null, "VIGENTE", "Motivo", Instant.EPOCH, CUENTA_ID, null,
				null, null, 0L, false);
	}
}
