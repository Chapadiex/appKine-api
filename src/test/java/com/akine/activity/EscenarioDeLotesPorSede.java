package com.akine.activity;

import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeLote;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Lo comun a los ITs del limite de lotes por sede (U-49 c, DP-30): un tenant con <b>dos clases en
 * curso en la misma sede</b>, dos inscriptos en cada una.
 *
 * <p>Para tener "un lote en curso" sin sortear tiempos, el lote A se larga contra una clase cuya
 * fila retiene otra conexion con {@code FOR UPDATE} ({@link CarreraDirigida}): su primer item queda
 * esperando el {@code FOR SHARE} de la clase con el lugar de la sede ya tomado. El lote B va a
 * <b>otra</b> clase de la misma sede, que nadie retiene: lo unico que puede demorarlo es el limite.
 */
abstract class EscenarioDeLotesPorSede {

	/** La sentencia con la que el primer item del lote A se queda esperando la clase. */
	static final String LOCK_DE_LA_CLASE = "%FROM clase_programada%FOR SHARE%";

	@Autowired AsistenciaService asistenciaService;
	@Autowired InscripcionService inscripcionService;
	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource dataSource;
	@Autowired MeterRegistry metricas;

	Tenant tenant;
	long claseA;
	long claseB;
	private ActividadItFixture fx;

	@BeforeEach
	void prepararDosClasesDeLaMismaSede() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
		tenant = fx.crearTenant(20, 2);
		claseA = claseEnCurso(1);
		claseB = claseEnCurso(2);
	}

	/** Un lote de dos items: el que pasa por el limite. */
	ResultadoDeLote lote(long clase) {
		return asistenciaService.registrarLote(tenant.actor(), tenant.consultorioId(), clase,
				List.of(marca(clase, 0, OrigenAsistencia.LOTE), marca(clase, 1, OrigenAsistencia.LOTE)));
	}

	RegistrarAsistenciaCommand marca(long clase, int persona, OrigenAsistencia origen) {
		return new RegistrarAsistenciaCommand(fx.inscripcionDe(clase, tenant.persona(persona)),
				ResultadoAsistencia.PRESENTE, origen, null, null);
	}

	int asistenciasDe(long clase) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM asistencia_actividad WHERE clase_id = ?", Integer.class, clase);
	}

	double contador(String resultado) {
		return metricas.get("akine.asistencia.lotes").tag("resultado", resultado).counter().count();
	}

	double enEspera() {
		return metricas.get("akine.asistencia.lotes.en.espera").gauge().value();
	}

	private long claseEnCurso(int diasAdelante) {
		long clase = fx.programarClase(tenant, 10, diasAdelante);
		fx.inscribir(tenant, clase, tenant.persona(0), false);
		fx.inscribir(tenant, clase, tenant.persona(1), false);
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		return clase;
	}

	static void esperarHasta(BooleanSupplier condicion) throws InterruptedException {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condicion.getAsBoolean()) {
			if (System.nanoTime() > limite) {
				throw new AssertionError("La condicion no se cumplio en 10 s");
			}
			Thread.sleep(20);
		}
	}
}
