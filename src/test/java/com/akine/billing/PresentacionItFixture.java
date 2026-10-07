package com.akine.billing;

import com.akine.billing.application.OperatingActor;
import com.akine.billing.application.PresentacionCommands;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Lo que comparten los ITs de presentaciones de 07.04: un tenant con su sede, un administrativo
 * que tiene {@code cobro:register}, un paciente, una oferta y un financiador.
 *
 * <h2>Las obligaciones del financiador se insertan por JDBC</h2>
 *
 * <p>Hasta AKINE F-4 ningun camino del producto devengaba una obligacion con
 * {@code responsable = 'FINANCIADOR'}. Desde F-4 las devenga el cierre de una sesion cubierta por
 * un convenio —{@code ObligacionDelFinanciadorIT} lo prueba de punta a punta—, pero estos ITs miden
 * los lotes y no el devengo, y armar convenio, cobertura y cierre por cada fila solo los haria mas
 * lentos. Se insertan respetando los CHECK de {@code V56} y {@code V77}: {@code FINANCIADOR} con
 * {@code financiador_id}, concepto {@code FINANCIADOR} y el snapshot de convenio entero.
 *
 * <p>Cada obligacion lleva su <b>propia sesion</b>, porque {@code uk_obligacion_prestacion} es
 * {@code (sesion_id, responsable, deleted_key)}: una prestacion devenga una sola deuda por
 * responsable.
 *
 * <p>Los lotes, en cambio, se arman, confirman y cobran por {@link PresentacionService} y
 * {@code FinanciadorPagoService}: los {@code UPDATE} nativos, el numerador y la columna generada son
 * justamente lo que estos tests miden.
 */
final class PresentacionItFixture {

	static final String ZONA = "America/Argentina/Cordoba";
	static final String MONEDA = "ARS";

	private final JdbcTemplate jdbc;
	private final PresentacionService presentacionService;

	PresentacionItFixture(JdbcTemplate jdbc, PresentacionService presentacionService) {
		this.jdbc = jdbc;
		this.presentacionService = presentacionService;
	}

	/** Un tenant listo para presentar: sede, ADMINISTRATIVO, paciente con historia, oferta y un financiador. */
	record Tenant(
			long organizationId, long consultorioId, long cuentaId, long personaId,
			long historiaId, long ofertaId, long financiadorId, OperatingActor actor) {
	}

	Tenant crearTenant() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + sufijo, "presentacion-it-" + sufijo, ZONA);
		long organizationId = ultimoId();

		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "Sede " + sufijo, ZONA);
		long consultorioId = ultimoId();

		String email = "presentacion-it-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		long cuentaId = ultimoId();

		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'ADMINISTRATIVO', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, cuentaId);

		String apellido = "Paciente" + sufijo;
		jdbc.update("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, apellido, apellido.toUpperCase());
		long personaId = ultimoId();

		jdbc.update("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, cuentaId);
		long historiaId = ultimoId();

		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "PRES-" + sufijo.toUpperCase(), "Servicio " + sufijo);
		long servicioId = ultimoId();

		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 1, 0, 0, 0, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, servicioId, "Oferta " + sufijo);
		long ofertaId = ultimoId();

		long financiadorId = insertarFinanciador(organizationId, sufijo);

		return new Tenant(organizationId, consultorioId, cuentaId, personaId, historiaId, ofertaId,
				financiadorId, new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	long insertarFinanciador(long organizationId, String sufijo) {
		jdbc.update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'OBRA_SOCIAL', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "FIN-" + sufijo, "Financiador " + sufijo);
		return ultimoId();
	}

	/**
	 * Una deuda a nombre del financiador, devengada ahora, sobre una sesion propia.
	 *
	 * <p>Ver la cabecera: es la fila que el devengado todavia no sabe escribir.
	 */
	long obligacionDeFinanciador(Tenant tenant, long financiadorId, String importe) {
		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), tenant.consultorioId(), tenant.historiaId(),
				tenant.ofertaId(), 1L, tenant.cuentaId());
		long sesionId = ultimoId();

		// Desde V77 la parte del financiador lleva el snapshot de convenio ENTERO
		// (ck_obligacion_snapshot_convenio_coherente). Los ids son sinteticos: las columnas de
		// snapshot son una copia y no tienen FK.
		BigDecimal monto = new BigDecimal(importe);
		jdbc.update("""
				INSERT INTO obligacion (organization_id, consultorio_id, sesion_id, persona_id,
				                        responsable, financiador_id, concepto, practica_id,
				                        cobertura_id, importe_original, saldo, moneda,
				                        estado, oferta_id, snapshot_nombre, snapshot_precio,
				                        snapshot_convenio_id, snapshot_arancel_id, snapshot_plan_id,
				                        snapshot_convenio_codigo, snapshot_convenio_nombre,
				                        snapshot_importe_total, snapshot_importe_financiador,
				                        snapshot_coseguro, snapshot_requeria_orden,
				                        snapshot_requeria_autorizacion, snapshot_requeria_credencial,
				                        snapshot_credencial_vencida, snapshot_vigente_el,
				                        snapshot_capturado_en,
				                        devengada_en, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'FINANCIADOR', ?, 'FINANCIADOR', 1, 1, ?, ?, 'ARS', 'PENDIENTE',
				        ?, 'Sesion sintetica', ?,
				        1, 1, 1, 'CONV-SINT', 'Convenio sintetico', ?, ?, 0.00, 0, 0, 0, 0,
				        CURDATE(), UTC_TIMESTAMP(6),
				        UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), tenant.consultorioId(), sesionId, tenant.personaId(),
				financiadorId, monto, monto, tenant.ofertaId(), monto, monto, monto);
		return ultimoId();
	}

	/** Un borrador vacio del periodo que contiene a hoy, para el financiador dado. */
	PresentacionView borrador(Tenant tenant, long financiadorId) {
		return borradorCon(tenant, financiadorId, List.of());
	}

	PresentacionView borradorCon(Tenant tenant, long financiadorId, List<Long> obligacionIds) {
		LocalDate hoy = LocalDate.now(ZoneId.of(ZONA));
		return presentacionService.crear(tenant.actor(), tenant.consultorioId(),
				new PresentacionCommands.Alta(financiadorId, hoy.minusDays(30), hoy.plusDays(30),
						MONEDA, obligacionIds));
	}

	/** Un lote PRESENTADO de una sola prestacion por el importe dado. */
	PresentacionView loteConfirmado(Tenant tenant, String importe) {
		long obligacionId = obligacionDeFinanciador(tenant, tenant.financiadorId(), importe);
		PresentacionView borrador = borradorCon(tenant, tenant.financiadorId(), List.of(obligacionId));
		return presentacionService.confirmar(tenant.actor(), tenant.consultorioId(), borrador.id());
	}

	/** Una jornada abierta HOY, insertada directo: lo que se mide es el pago, no la apertura. */
	long abrirJornada(Tenant tenant, String saldoInicial) {
		jdbc.update("""
				INSERT INTO jornada_caja (organization_id, consultorio_id, fecha_negocio, moneda,
				                          estado, saldo_inicial, saldo_arqueo, abierta_en,
				                          abierta_por_cuenta_id, created_at, updated_at)
				VALUES (?, ?, ?, 'ARS', 'ABIERTA', ?, ?, UTC_TIMESTAMP(6), ?,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), tenant.consultorioId(),
				Date.valueOf(LocalDate.now(ZoneId.of(ZONA))), new BigDecimal(saldoInicial),
				new BigDecimal(saldoInicial), tenant.cuentaId());
		return ultimoId();
	}

	private long ultimoId() {
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	// ---------------------------------------------------------------------------------
	// Concurrencia
	// ---------------------------------------------------------------------------------

	record Desenlace(Object resultado, Exception error) {

		boolean fallo() {
			return error != null;
		}

		boolean falloPor(Class<? extends Throwable> tipo) {
			for (Throwable actual = error; actual != null; actual = actual.getCause()) {
				if (tipo.isInstance(actual)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK";
		}
	}

	/** La barrera es lo que hace real la carrera: sin ella la primera suele terminar antes. */
	static List<Desenlace> enParalelo(List<Callable<?>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<?> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace(null, error);
					}
				}));
			}
			List<Desenlace> desenlaces = new ArrayList<>();
			for (Future<Desenlace> futuro : futuros) {
				desenlaces.add(futuro.get(60, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception fallo) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", fallo);
		}
	}
}
