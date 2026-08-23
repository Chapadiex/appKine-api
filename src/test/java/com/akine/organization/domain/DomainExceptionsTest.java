package com.akine.organization.domain;

import com.akine.organization.domain.exception.ContextNotAuthorizedException;
import com.akine.organization.domain.exception.FeatureNotAvailableException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PlanLimitExceededException;
import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import com.akine.organization.spi.FeatureCode;
import com.akine.organization.spi.LimitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el contrato de las excepciones de dominio del modulo.
 *
 * <p>Dos garantias, y ninguna es cosmetica. Primera: el contexto para diagnosticar
 * —organizacion, limite, feature— viaja en campos tipados y no dentro del texto, porque el
 * handler HTTP arma la respuesta con los campos y usa el mensaje solo para el log. Segunda:
 * <b>ningun mensaje filtra datos del tenant</b>. Los mensajes de excepcion terminan en logs
 * compartidos y a veces, por descuido, en el cuerpo de la respuesta.
 */
class DomainExceptionsTest {

	@Test
	@DisplayName("La organizacion inexistente y la ajena dan el mismo mensaje")
	void inexistente_y_ajena_son_indistinguibles() {
		OrganizationNotFoundException noExiste = new OrganizationNotFoundException(1L);
		OrganizationNotFoundException deOtroTenant = new OrganizationNotFoundException(999L);

		// Distinguir los dos casos filtra la existencia de organizaciones ajenas: alcanza con
		// probar ids para enumerar los clientes del SaaS. Por eso el mensaje es identico y no
		// menciona el id, que viaja aparte para el log correlacionado.
		assertThat(noExiste.getMessage()).isEqualTo(deOtroTenant.getMessage());
		assertThat(noExiste.getMessage()).doesNotContain("1").doesNotContain("999");
		assertThat(noExiste.getOrganizationId()).isEqualTo(1L);
		assertThat(deOtroTenant.getOrganizationId()).isEqualTo(999L);
	}

	@Test
	@DisplayName("El contexto no autorizado no dice cual de las tres piezas fallo")
	void el_contexto_no_autorizado_no_detalla() {
		ContextNotAuthorizedException error = new ContextNotAuthorizedException(42L, 7L, 13L);

		// Se traduce a 404 y no a 403: responder "prohibido" confirmaria que esa organizacion
		// y ese consultorio existen. El mensaje tampoco puede nombrar los ids.
		assertThat(error.getMessage())
				.doesNotContain("42")
				.doesNotContain("7")
				.doesNotContain("13");
		assertThat(error.getAccountId()).isEqualTo(42L);
		assertThat(error.getOrganizationId()).isEqualTo(7L);
		assertThat(error.getConsultorioId()).isEqualTo(13L);
	}

	@Test
	@DisplayName("El limite excedido informa codigo, tope y uso actual")
	void el_limite_excedido_informa_el_detalle() {
		PlanLimitExceededException error =
				new PlanLimitExceededException(LimitCode.MAX_CONSULTORIOS, 3, 3L);

		// Es una regla de negocio, no un problema de permisos: el usuario tiene derecho a la
		// operacion y lo que no alcanza es el plan. Necesita saber que limite toco y cuanto
		// vale para poder decidir el upgrade; sin eso la respuesta 409 no es accionable.
		assertThat(error.getLimitCode()).isEqualTo(LimitCode.MAX_CONSULTORIOS);
		assertThat(error.getLimitValue()).isEqualTo(3);
		assertThat(error.getCurrentUsage()).isEqualTo(3L);
		assertThat(error.getMessage()).contains("MAX_CONSULTORIOS").contains("3");
	}

	@Test
	@DisplayName("La feature no disponible nombra la funcionalidad pedida")
	void la_feature_no_disponible_nombra_la_funcion() {
		FeatureNotAvailableException error =
				new FeatureNotAvailableException(FeatureCode.REPORTES_AVANZADOS);

		assertThat(error.getFeatureCode()).isEqualTo(FeatureCode.REPORTES_AVANZADOS);
		assertThat(error.getMessage()).contains("REPORTES_AVANZADOS");
	}

	@Test
	@DisplayName("La suspension identifica al tenant sin exponer datos de negocio")
	void la_suspension_identifica_el_tenant() {
		SubscriptionSuspendedException error = new SubscriptionSuspendedException(55L);

		assertThat(error.getOrganizationId()).isEqualTo(55L);
		// Suspender bloquea la operacion, jamas destruye (RN-M01-002): el mensaje explica que
		// la mutacion se rechaza, no que falten datos.
		assertThat(error.getMessage()).contains("suspendida");
	}

	@Test
	@DisplayName("El rechazo nombra el estado real: no dice 'suspendida' si esta cancelada")
	void el_rechazo_nombra_el_estado_real() {
		// Cambiar de plan exige ACTIVA, asi que tambien se rechaza con la suscripcion
		// CANCELADA. Reportar "suspendida" en ese caso manda al que diagnostica a buscar una
		// suspension que nunca ocurrio.
		SubscriptionSuspendedException cancelada =
				new SubscriptionSuspendedException(55L, SubscriptionStatus.CANCELADA);
		assertThat(cancelada.getMessage()).contains("CANCELADA").doesNotContain("suspendida");

		SubscriptionSuspendedException suspendida =
				new SubscriptionSuspendedException(55L, SubscriptionStatus.SUSPENDIDA);
		assertThat(suspendida.getMessage()).contains("SUSPENDIDA");

		// Sigue sin filtrar internals, igual que el resto.
		assertThat(cancelada.getMessage()).doesNotContain("com.akine").doesNotContain("subscription");
	}

	@Test
	@DisplayName("Ningun mensaje de dominio filtra internals")
	void ningun_mensaje_filtra_internals() {
		// Estos mensajes terminan en logs compartidos y, si alguien los encadena sin pensarlo,
		// en el cuerpo de la respuesta HTTP. No pueden nombrar clases, paquetes ni tablas.
		assertThat(new OrganizationNotFoundException(1L).getMessage())
				.doesNotContain("com.akine").doesNotContain("Exception");
		assertThat(new ContextNotAuthorizedException(1L, 2L, 3L).getMessage())
				.doesNotContain("com.akine").doesNotContain("membership");
		assertThat(new PlanLimitExceededException(LimitCode.MAX_MIEMBROS_ACTIVOS, 1, 5L)
				.getMessage())
				.doesNotContain("com.akine").doesNotContain("plan_limit");
		assertThat(new FeatureNotAvailableException(FeatureCode.NOTIFICACIONES_PACIENTE)
				.getMessage())
				.doesNotContain("com.akine").doesNotContain("plan_feature");
		assertThat(new SubscriptionSuspendedException(1L).getMessage())
				.doesNotContain("com.akine").doesNotContain("subscription");
	}

	@Test
	@DisplayName("Todas son no chequeadas: no obligan a decorar las firmas del dominio")
	void todas_son_no_chequeadas() {
		// Si fueran chequeadas, cada metodo del dominio arrastraria un throws y la tentacion
		// siguiente seria capturarlas vacias para sacarlo de la firma.
		assertThat(RuntimeException.class)
				.isAssignableFrom(OrganizationNotFoundException.class)
				.isAssignableFrom(ContextNotAuthorizedException.class)
				.isAssignableFrom(PlanLimitExceededException.class)
				.isAssignableFrom(FeatureNotAvailableException.class)
				.isAssignableFrom(SubscriptionSuspendedException.class);
	}
}
