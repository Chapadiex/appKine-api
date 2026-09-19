package com.akine.clinical.infrastructure;

import com.akine.clinical.application.CasoNumeradorIniciador;
import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoSesionNumeradorPort;
import com.akine.clinical.spi.CasoDirectory;
import com.akine.clinical.spi.CasoSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * La implementacion de {@link CasoDirectory}, del lado de {@code clinical}.
 *
 * <p>Vive en {@code infrastructure} y no en {@code application} por la misma razon que
 * {@link ClinicalHistoriaClinicaDirectory}: es un adaptador que traduce filas a la forma que el
 * {@code spi} declara. Lo poco de logica que tiene —el orden de los tres pasos del numerador— es
 * logica de persistencia, no de negocio.
 *
 * <p><b>No autoriza nada y no audita nada</b>, y las dos ausencias son deliberadas. Quien llama ya
 * evaluo su propio permiso; y lo que sale de aca no es contenido clinico, asi que no hay lectura
 * clinica que registrar. El dia que este spi devolviera diagnostico, esa afirmacion dejaria de ser
 * cierta — motivo de mas para que no lo devuelva.
 */
@Component
public class ClinicalCasoDirectory implements CasoDirectory {

	private final CasoClinicoRepositoryPort casos;
	private final CasoSesionNumeradorPort numerador;
	private final CasoNumeradorIniciador numeradorIniciador;

	public ClinicalCasoDirectory(
			CasoClinicoRepositoryPort casos,
			CasoSesionNumeradorPort numerador,
			CasoNumeradorIniciador numeradorIniciador) {

		this.casos = casos;
		this.numerador = numerador;
		this.numeradorIniciador = numeradorIniciador;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CasoSnapshot> find(long organizationId, long casoId) {
		return casos.findByIdAndOrganizationId(casoId, organizationId)
				.map(ClinicalCasoDirectory::instantanea);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Los tres pasos, en este orden y no en otro:
	 *
	 * <ol>
	 *   <li><b>Asegurar la fila</b>, en una transaccion aparte —{@code REQUIRES_NEW} dentro de
	 *       {@link CasoNumeradorIniciador}—. Crearla dentro de la transaccion que despues la
	 *       bloquea produce un deadlock entre los primeros N cierres concurrentes del mismo caso,
	 *       y el {@code try/catch} no salva porque atrapar una excepcion de persistencia no
	 *       des-marca la transaccion.</li>
	 *   <li><b>Incrementar</b>, con {@code UPDATE ultimo_numero + 1}. No hay lectura previa, y esa
	 *       es toda la diferencia con {@code MAX + 1}.</li>
	 *   <li><b>Leer</b>, en la misma transaccion y con el lock tomado, asi que lee su propia
	 *       escritura y nadie pudo incrementar en el medio.</li>
	 * </ol>
	 *
	 * <p>{@code Propagation.REQUIRED} —el default— es parte del contrato: esto se une a la
	 * transaccion del cierre de sesion y no abre una propia. Si abriera una, el numero se
	 * commitearia aunque el cierre despues fallara, y el caso quedaria con un hueco.
	 */
	@Override
	@Transactional
	public int siguienteNumeroDeSesion(long organizationId, long casoId) {
		numeradorIniciador.asegurarSesionesDelCaso(organizationId, casoId);
		numerador.incrementar(organizationId, casoId);
		Integer numero = numerador.leerUltimo(organizationId, casoId);
		if (numero == null) {
			// La fila se acaba de asegurar y de incrementar en esta misma transaccion: que no
			// exista significa que alguien la borro a mano entre las dos operaciones. Falla fuerte
			// en vez de devolver 0, que seria un correlativo invalido escrito en la historia.
			throw new IllegalStateException(
					"El numerador de sesiones del caso " + casoId + " no existe despues de crearlo");
		}
		return numero;
	}

	private static CasoSnapshot instantanea(CasoClinico caso) {
		return new CasoSnapshot(
				caso.getId(),
				caso.getOrganizationId(),
				caso.getHistoriaClinicaId(),
				caso.getNumeroCaso(),
				caso.estaActivo());
	}
}
