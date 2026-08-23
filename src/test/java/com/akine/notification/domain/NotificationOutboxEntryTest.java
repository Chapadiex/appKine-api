package com.akine.notification.domain;

import com.akine.notification.NotificationFixtures;
import com.akine.notification.domain.exception.InvalidOutboxTransitionException;
import com.akine.notification.spi.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static com.akine.notification.NotificationFixtures.AHORA;
import static com.akine.notification.NotificationFixtures.CLAVE;
import static com.akine.notification.NotificationFixtures.DESTINATARIO;
import static com.akine.notification.NotificationFixtures.ORG_ID;
import static com.akine.notification.NotificationFixtures.TOKEN_REF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La fila del outbox y su ciclo de entrega (RN-M26-001, RF-M26-004/005).
 *
 * <p>El estado solo cambia por los metodos de esta clase y cada uno valida la transicion antes
 * de mutar. Estos tests sostienen las dos mitades: que las transiciones validas dejen los
 * contadores y las marcas temporales como corresponde, y que una invalida no toque NADA —ni el
 * contador de intentos ni el motivo del ultimo fallo—, porque una mutacion parcial sobre una
 * fila que otro worker ya resolvio es indistinguible de un intento real que nunca ocurrio.
 */
class NotificationOutboxEntryTest {

	private static final Duration LEASE = Duration.ofMinutes(5);

	private NotificationOutboxEntry nueva() {
		return NotificationFixtures.nueva(
				NotificationType.ACTIVACION_CUENTA, NotificationFixtures.payloadCompleto());
	}

	@Test
	@DisplayName("una notificacion nace PENDIENTE, sin intentos y sin marca de envio")
	void nace_pendiente() {
		NotificationOutboxEntry entry = nueva();

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.PENDIENTE);
		assertThat(entry.getIntentos()).isZero();
		assertThat(entry.getMaxIntentos()).isEqualTo(5);
		assertThat(entry.getProximaEjecucionEn()).isEqualTo(AHORA);
		assertThat(entry.getProcesandoDesde()).isNull();
		assertThat(entry.getEnviadoEn()).isNull();
		assertThat(entry.getErrorSanitizado()).isNull();
		assertThat(entry.getTipo()).isEqualTo(NotificationType.ACTIVACION_CUENTA);
		assertThat(entry.getDestinatario()).isEqualTo(DESTINATARIO);
		assertThat(entry.getClaveIdempotente()).isEqualTo(CLAVE);
		assertThat(entry.getOrganizationId()).isEqualTo(ORG_ID);
		assertThat(entry.getReferenciaTokenId()).isEqualTo(TOKEN_REF);
		assertThat(entry.getId()).isNull();
	}

	@Test
	@DisplayName("la fila guarda el payload sanitizado y jamas el enlace")
	void guarda_el_payload_sanitizado() {
		NotificationOutboxEntry entry = nueva();

		// La referencia al token es un id opaco, no el token: si aca hubiera un valor usable,
		// la tabla de diagnostico pasaria a ser un deposito de credenciales (T-11, RN-M02-003).
		assertThat(entry.getPayloadSanitizado())
				.doesNotContain("http")
				.doesNotContain("token");
		assertThat(entry.payload().get("nombre")).isEqualTo("Ana");
		assertThat(entry.payload().get("organizacionNombre")).isEqualTo("Kine Sur");
	}

	@Test
	@DisplayName("un tipo que necesita enlace exige la referencia al token al encolar")
	void el_tipo_con_enlace_exige_referencia() {
		// Si se aceptara sin referencia, el fallo aparecerria recien en el worker, horas despues
		// y sobre una fila que ya no se puede corregir sin intervencion manual.
		assertThatThrownBy(() -> new NotificationOutboxEntry(
				NotificationType.ACTIVACION_CUENTA, DESTINATARIO, CLAVE, ORG_ID, null,
				SanitizedPayload.vacio(), 5, AHORA))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ACTIVACION_CUENTA");

		assertThatThrownBy(() -> new NotificationOutboxEntry(
				NotificationType.RECUPERACION_PASSWORD, DESTINATARIO, CLAVE, ORG_ID, "   ",
				SanitizedPayload.vacio(), 5, AHORA))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("un tipo sin enlace se encola sin referencia al token")
	void el_tipo_sin_enlace_no_exige_referencia() {
		NotificationOutboxEntry entry = new NotificationOutboxEntry(
				NotificationType.CUENTA_YA_REGISTRADA, DESTINATARIO, CLAVE, null, null,
				SanitizedPayload.vacio(), 3, AHORA);

		// organizationId nulo es legitimo: el aviso puede ser previo a cualquier tenant.
		assertThat(entry.getReferenciaTokenId()).isNull();
		assertThat(entry.getOrganizationId()).isNull();
		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.PENDIENTE);
	}

	@Test
	@DisplayName("los datos obligatorios de la fila se validan al encolar")
	void los_datos_obligatorios_se_validan() {
		assertThatThrownBy(() -> new NotificationOutboxEntry(
				null, DESTINATARIO, CLAVE, ORG_ID, TOKEN_REF, SanitizedPayload.vacio(), 5, AHORA))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("tipo");

		assertThatThrownBy(() -> new NotificationOutboxEntry(
				NotificationType.CUENTA_YA_REGISTRADA, "  ", CLAVE, ORG_ID, null,
				SanitizedPayload.vacio(), 5, AHORA))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("destinatario");

		assertThatThrownBy(() -> new NotificationOutboxEntry(
				NotificationType.CUENTA_YA_REGISTRADA, DESTINATARIO, null, ORG_ID, null,
				SanitizedPayload.vacio(), 5, AHORA))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("claveIdempotente");

		assertThatThrownBy(() -> new NotificationOutboxEntry(
				NotificationType.CUENTA_YA_REGISTRADA, DESTINATARIO, CLAVE, ORG_ID, null,
				null, 5, AHORA))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("payload");

		assertThatThrownBy(() -> new NotificationOutboxEntry(
				NotificationType.CUENTA_YA_REGISTRADA, DESTINATARIO, CLAVE, ORG_ID, null,
				SanitizedPayload.vacio(), 5, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("proximaEjecucionEn");
	}

	@Test
	@DisplayName("el worker que la toma deja el lease y la fila queda PROCESANDO")
	void al_tomarla_queda_procesando_con_lease() {
		NotificationOutboxEntry entry = nueva();

		entry.marcarEnProceso(AHORA);

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.PROCESANDO);
		assertThat(entry.getProcesandoDesde()).isEqualTo(AHORA);
		// Reclamar no es intentar: el intento se cuenta cuando hay un resultado del envio.
		assertThat(entry.getIntentos()).isZero();
	}

	@ParameterizedTest
	@EnumSource(value = OutboxStatus.class, names = {"PENDIENTE", "REINTENTABLE"},
			mode = EnumSource.Mode.EXCLUDE)
	@DisplayName("una fila que no esta en la cola no la puede tomar el worker")
	void solo_se_toma_lo_reclamable(OutboxStatus estado) {
		NotificationOutboxEntry entry = NotificationFixtures.en(estado);

		assertThatThrownBy(() -> entry.marcarEnProceso(AHORA))
				.isInstanceOf(InvalidOutboxTransitionException.class);
	}

	@Test
	@DisplayName("la entrega exitosa cuenta el intento, libera el lease y fecha el envio")
	void la_entrega_exitosa_cierra_la_fila() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);
		Instant enviado = AHORA.plusSeconds(3);

		entry.marcarEnviada(enviado);

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.ENVIADA);
		assertThat(entry.getEnviadoEn()).isEqualTo(enviado);
		assertThat(entry.getProcesandoDesde()).isNull();
		assertThat(entry.getIntentos()).isEqualTo(1);
	}

	@Test
	@DisplayName("una entrega exitosa despues de fallar conserva el motivo del ultimo fallo")
	void el_exito_no_borra_el_motivo_anterior() {
		// Que una notificacion haya entrado despues de tres fallos es informacion util para
		// diagnosticar, y el motivo ya viene sanitizado: borrarlo solo esconde el sintoma.
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.REINTENTABLE);
		entry.marcarEnProceso(AHORA.plusSeconds(60));

		entry.marcarEnviada(AHORA.plusSeconds(61));

		assertThat(entry.getErrorSanitizado()).isEqualTo("smtp caido");
		assertThat(entry.getIntentos()).isEqualTo(2);
	}

	@Test
	@DisplayName("un fallo transitorio con intentos disponibles reprograma con la espera dada")
	void el_fallo_transitorio_reprograma() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);

		OutboxStatus resultado = entry.registrarFalloTransitorio(
				AHORA, "conexion rechazada", Optional.of(Duration.ofMinutes(5)));

		assertThat(resultado).isEqualTo(OutboxStatus.REINTENTABLE);
		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.REINTENTABLE);
		assertThat(entry.getIntentos()).isEqualTo(1);
		assertThat(entry.getProcesandoDesde()).isNull();
		assertThat(entry.getProximaEjecucionEn()).isEqualTo(AHORA.plus(Duration.ofMinutes(5)));
		assertThat(entry.getErrorSanitizado()).isEqualTo("conexion rechazada");
	}

	@Test
	@DisplayName("un fallo transitorio sin espera disponible agota la notificacion")
	void el_fallo_transitorio_sin_espera_agota() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);
		Instant programadaOriginal = entry.getProximaEjecucionEn();

		OutboxStatus resultado = entry.registrarFalloTransitorio(
				AHORA.plusSeconds(10), "smtp sigue caido", Optional.empty());

		assertThat(resultado).isEqualTo(OutboxStatus.AGOTADA);
		assertThat(entry.getIntentos()).isEqualTo(1);
		// Una fila agotada no vuelve a la cola: reprogramarla seria dejarla girando igual.
		assertThat(entry.getProximaEjecucionEn()).isEqualTo(programadaOriginal);
	}

	@Test
	@DisplayName("el motivo de un fallo se guarda sanitizado, sin enlaces ni credenciales")
	void el_motivo_se_guarda_sanitizado() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);

		entry.registrarFalloTransitorio(AHORA,
				"fallo el relay smtp.proveedor.test:587 con password=hunter2 al mandar a "
						+ "ana.gomez@ejemplo.test el enlace https://app.akine.test/a?token=SECRETO",
				Optional.of(Duration.ofMinutes(1)));

		// error_sanitizado se lee desde una pantalla administrativa y termina en los backups.
		assertThat(entry.getErrorSanitizado())
				.doesNotContain("hunter2")
				.doesNotContain("SECRETO")
				.doesNotContain("smtp.proveedor.test:587")
				.doesNotContain("ana.gomez@ejemplo.test")
				.contains("a***@ejemplo.test");
	}

	@Test
	@DisplayName("un fallo permanente cierra la fila sin dejarla en la cola")
	void el_fallo_permanente_cierra_la_fila() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);

		entry.registrarFalloPermanente("destinatario invalido");

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.FALLIDA);
		assertThat(entry.getIntentos()).isEqualTo(1);
		assertThat(entry.getProcesandoDesde()).isNull();
		assertThat(entry.getErrorSanitizado()).isEqualTo("destinatario invalido");
	}

	@Test
	@DisplayName("registrar un resultado sobre una fila que ya no esta PROCESANDO no muta nada")
	void un_resultado_tardio_no_muta_nada() {
		// Es la condicion que hace inocuo reprocesar: si la validacion corriera despues de tocar
		// los campos, un resultado repetido dejaria el intento consumido y el motivo pisado
		// aunque la excepcion impidiera el cambio de estado (RF-M26-005, RN-M26-003).
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.ENVIADA);
		int intentosAntes = entry.getIntentos();
		String errorAntes = entry.getErrorSanitizado();
		Instant programadaAntes = entry.getProximaEjecucionEn();

		assertThatThrownBy(() -> entry.registrarFalloTransitorio(
				AHORA, "resultado duplicado", Optional.of(Duration.ofMinutes(1))))
				.isInstanceOf(InvalidOutboxTransitionException.class);
		assertThatThrownBy(() -> entry.registrarFalloPermanente("resultado duplicado"))
				.isInstanceOf(InvalidOutboxTransitionException.class);
		assertThatThrownBy(() -> entry.marcarEnviada(AHORA))
				.isInstanceOf(InvalidOutboxTransitionException.class);

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.ENVIADA);
		assertThat(entry.getIntentos()).isEqualTo(intentosAntes);
		assertThat(entry.getErrorSanitizado()).isEqualTo(errorAntes);
		assertThat(entry.getProximaEjecucionEn()).isEqualTo(programadaAntes);
	}

	@Test
	@DisplayName("una fila huerfana vuelve a REINTENTABLE conservando el contador de intentos")
	void la_fila_huerfana_vuelve_a_la_cola() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.REINTENTABLE);
		entry.marcarEnProceso(AHORA.plusSeconds(60));
		int intentosAntes = entry.getIntentos();

		entry.recuperarLeaseVencido(AHORA.plusSeconds(600), Duration.ofMinutes(5));

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.REINTENTABLE);
		assertThat(entry.getProcesandoDesde()).isNull();
		assertThat(entry.getProximaEjecucionEn())
				.isEqualTo(AHORA.plusSeconds(600).plus(Duration.ofMinutes(5)));
		// No hubo un envio fallido sino un proceso caido: no consume un intento. Pero tampoco
		// vuelve a PENDIENTE, o la fila podria girar para siempre sin agotarse nunca.
		assertThat(entry.getIntentos()).isEqualTo(intentosAntes);
		assertThat(entry.getErrorSanitizado()).contains("Lease vencido");
	}

	@Test
	@DisplayName("el lease vence solo cuando la fila esta PROCESANDO y paso la duracion completa")
	void el_lease_vence_solo_cuando_corresponde() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);

		assertThat(entry.leaseVencido(AHORA.plus(LEASE).plusSeconds(1), LEASE)).isTrue();
		// Justo en el limite todavia no vencio: si venciera, el worker se robaria a si mismo una
		// fila que puede estar terminando de enviar en ese preciso instante.
		assertThat(entry.leaseVencido(AHORA.plus(LEASE), LEASE)).isFalse();
		assertThat(entry.leaseVencido(AHORA.plusSeconds(1), LEASE)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(value = OutboxStatus.class, names = "PROCESANDO", mode = EnumSource.Mode.EXCLUDE)
	@DisplayName("una fila que no esta PROCESANDO nunca tiene el lease vencido")
	void sin_procesando_no_hay_lease(OutboxStatus estado) {
		NotificationOutboxEntry entry = NotificationFixtures.en(estado);

		assertThat(entry.leaseVencido(AHORA.plusSeconds(99999), LEASE)).isFalse();
	}

	@Test
	@DisplayName("el reintento administrativo reinicia el contador y devuelve la fila a la cola")
	void el_reintento_administrativo_reinicia_el_contador() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.AGOTADA);
		Instant reintento = AHORA.plusSeconds(7200);

		entry.reintentarManualmente(reintento);

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.REINTENTABLE);
		assertThat(entry.getIntentos()).isZero();
		assertThat(entry.getProcesandoDesde()).isNull();
		// Sin espera: quien aprieta el boton quiere que salga ahora.
		assertThat(entry.getProximaEjecucionEn()).isEqualTo(reintento);
	}

	@ParameterizedTest
	@EnumSource(value = OutboxStatus.class, names = {"FALLIDA", "AGOTADA", "PROCESANDO"},
			mode = EnumSource.Mode.EXCLUDE)
	@DisplayName("el reintento administrativo no toca una fila encolada ni una ya entregada")
	void el_reintento_administrativo_no_toca_lo_vivo(OutboxStatus estado) {
		// Reabrir una ENVIADA es mandarle el mismo mail de nuevo a una persona real.
		NotificationOutboxEntry entry = NotificationFixtures.en(estado);

		assertThatThrownBy(() -> entry.reintentarManualmente(AHORA))
				.isInstanceOf(InvalidOutboxTransitionException.class);
	}

	@Test
	@DisplayName("el reintento administrativo tampoco reabre una fila en vuelo")
	void el_reintento_administrativo_no_reabre_lo_en_vuelo() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);

		assertThatThrownBy(() -> entry.reintentarManualmente(AHORA))
				.isInstanceOf(InvalidOutboxTransitionException.class);
	}

	@Test
	@DisplayName("el payload guardado se relee tal cual se encolo")
	void el_payload_se_relee_igual() {
		NotificationOutboxEntry entry = NotificationFixtures.nueva(
				NotificationType.INVITACION_COLABORADOR,
				SanitizedPayload.of(Map.of("nombre", "Ana \"la jefa\", Perez")));

		assertThat(entry.payload().get("nombre")).isEqualTo("Ana \"la jefa\", Perez");
	}

	@Test
	@DisplayName("las marcas temporales se fijan al insertar y solo updated_at cambia al actualizar")
	void las_marcas_temporales_se_fijan_una_sola_vez() {
		NotificationOutboxEntry entry = nueva();
		assertThat(entry.getCreatedAt()).isNull();
		assertThat(entry.getUpdatedAt()).isNull();

		entry.alInsertar();
		Instant creado = entry.getCreatedAt();

		assertThat(creado).isNotNull();
		assertThat(entry.getUpdatedAt()).isEqualTo(creado);

		entry.alActualizar();

		// created_at es la fecha de alta de la notificacion: si se pisara en cada update, se
		// perderia cuanto tiempo estuvo la fila sin entregarse, que es el dato de diagnostico.
		assertThat(entry.getCreatedAt()).isEqualTo(creado);
		assertThat(entry.getUpdatedAt()).isAfterOrEqualTo(creado);
	}

	@Test
	@DisplayName("un insert repetido no pisa la fecha de alta")
	void un_insert_repetido_no_pisa_el_alta() {
		NotificationOutboxEntry entry = nueva();
		entry.alInsertar();
		Instant creado = entry.getCreatedAt();

		entry.alInsertar();

		assertThat(entry.getCreatedAt()).isEqualTo(creado);
	}

	@Test
	@DisplayName("el constructor sin argumentos que necesita JPA sigue existiendo")
	void el_constructor_de_jpa_existe() throws Exception {
		// Hibernate necesita el constructor sin argumentos; el test lo fija para que nadie lo
		// borre "porque no se usa" y rompa la hidratacion recien en un test de integracion.
		Constructor<NotificationOutboxEntry> constructor =
				NotificationOutboxEntry.class.getDeclaredConstructor();
		constructor.setAccessible(true);

		assertThatCode(constructor::newInstance).doesNotThrowAnyException();
	}
}
