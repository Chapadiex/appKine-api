package com.akine.clinical.domain.port;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.CasoEvento;
import com.akine.clinical.domain.CasoProfesional;

import java.util.List;
import java.util.Optional;

/**
 * Los puertos de persistencia del Caso Clinico, declarados en {@code domain}.
 *
 * <p>Viven aca y no en {@code infrastructure} por la regla que 01.01 dejo fijada: {@code
 * application} consume <b>puertos en {@code domain}</b>, nunca repositorios de
 * {@code infrastructure}. La direccion permitida es {@code infrastructure -> application}, y
 * ArchUnit la verifica.
 *
 * <p>Van en un archivo propio y no dentro de {@code ClinicalRepositoryPorts} porque aquel ya
 * agrupa cinco puertos: sumarle cuatro mas convertiria el archivo en el lugar donde hay que
 * buscar todo del modulo, que es el primer paso hacia la capa global que AGENT.md seccion 4
 * regla 3 prohibe.
 *
 * <p><b>Toda firma lleva {@code organizationId}, sin excepcion.</b> No hay ni un metodo que
 * resuelva por id pelado: un {@code findById} en un modulo clinico es una fuga de tenant esperando
 * a que alguien lo llame desde un camino que no filtro antes.
 */
public final class CasoRepositoryPorts {

	private CasoRepositoryPorts() {
	}

	public interface CasoClinicoRepositoryPort {

		CasoClinico save(CasoClinico caso);

		Optional<CasoClinico> findByIdAndOrganizationId(Long id, Long organizationId);

		/**
		 * La misma consulta, pero forzando el avance de la version del caso al commitear.
		 *
		 * <p><b>Es lo que protege el cambio de equipo</b>, y es la leccion de 02.07 traida hasta
		 * aca: un {@code @Version} sobre el padre <b>no protege una escritura que solo toca tablas
		 * hijas</b>. Cambiar el equipo escribe en {@code caso_profesional} y no toca ni una columna
		 * de {@code caso_clinico}, asi que sin {@code OPTIMISTIC_FORCE_INCREMENT} dos cambios
		 * concurrentes commitean los dos, cada uno creyendo que partio del equipo que leyo, y el
		 * resultado no es ninguno de los dos.
		 *
		 * <p>Se usa <b>solo</b> para el equipo. La edicion, el cierre y la reapertura modifican
		 * columnas de la propia fila y JPA les sube la version sola.
		 */
		Optional<CasoClinico> findWithLockByIdAndOrganizationId(Long id, Long organizationId);

		/**
		 * Los casos de una historia, mas recientes primero.
		 *
		 * @param soloActivos {@code true} deja afuera los cerrados; {@code false} los incluye, que
		 *                    es lo que hace consultable el historico del paciente. <b>Un caso
		 *                    cerrado no es un caso borrado</b> (regla maestra 10)
		 */
		List<CasoClinico> buscarDeHistoria(
				Long organizationId, Long historiaClinicaId, boolean soloActivos);

		/**
		 * Los casos <b>ACTIVOS</b> de esa historia que ya usan esa oferta.
		 *
		 * <p>Es el detector de duplicado razonable, y es deliberadamente estricto: misma historia,
		 * misma oferta, estado activo. Nada difuso. Un detector generoso produce una advertencia en
		 * casi toda alta, el profesional aprende a confirmar sin leer, y la regla se vuelve un
		 * click de mas que no protege nada — la misma leccion que {@code ClaveDeBusqueda} dejo en
		 * el alta de Persona.
		 *
		 * <p><b>No garantiza el invariante, porque no hay invariante que garantizar:</b>
		 * RN-M10-002 admite dos casos activos de la misma oferta. Esto detiene el alta para que
		 * alguien mire, no la prohibe.
		 */
		List<CasoClinico> buscarActivosPorOferta(
				Long organizationId, Long historiaClinicaId, Long ofertaId);
	}

	/**
	 * El equipo tratante.
	 *
	 * <p>No hay {@code delete}: la salida del equipo es una fecha y no un borrado, porque quien
	 * trato al paciente lo trato (RF-M10-005, regla maestra 10).
	 */
	public interface CasoProfesionalRepositoryPort {

		CasoProfesional save(CasoProfesional participacion);

		List<CasoProfesional> saveAll(List<CasoProfesional> participaciones);

		/**
		 * Las participaciones de un caso.
		 *
		 * @param soloVigentes {@code true} devuelve quien esta hoy en el equipo; {@code false}
		 *                     devuelve tambien a quien estuvo, que es lo que hace que el historial
		 *                     del caso sirva
		 */
		List<CasoProfesional> buscarDeCaso(Long organizationId, Long casoId, boolean soloVigentes);
	}

	/**
	 * El historial de estados. <b>Append-only</b>: no declara {@code update} ni {@code delete}, y
	 * la ausencia es el contrato — un historial que se puede editar no es un historial.
	 */
	public interface CasoEventoRepositoryPort {

		CasoEvento save(CasoEvento evento);

		/** Los eventos de un caso, del mas viejo al mas nuevo: se lee como una linea de tiempo. */
		List<CasoEvento> buscarDeCaso(Long organizationId, Long casoId);
	}

	/**
	 * Secuencia de casos por historia clinica.
	 *
	 * <p><b>Tres operaciones y no una</b>, para que el orden quede a la vista de quien lee el
	 * servicio: asegurar la fila —en su propia transaccion—, incrementar y leer. Esconderlo detras
	 * de un solo {@code siguiente()} haria invisible que el primer paso NO puede ir dentro de la
	 * transaccion que despues la bloquea: esa es la creacion perezosa que produce deadlock y que
	 * este repositorio ya pago cuatro veces. Mismo reparto que {@code SesionNumeradorPort}.
	 */
	public interface CasoNumeradorPort {

		/** Crea la fila si falta. Sin lanzar. Va en una transaccion aparte: ver la implementacion. */
		void crearSiFalta(long organizationId, long historiaClinicaId);

		/** Incrementa el contador tomando el lock de fila. Serializa las altas de esa historia. */
		void incrementar(long organizationId, long historiaClinicaId);

		/** Lee el numero recien asignado. Se llama despues de {@link #incrementar}, en la misma transaccion. */
		Integer leerUltimo(long organizationId, long historiaClinicaId);
	}

	/**
	 * Secuencia de sesiones <b>dentro</b> de un caso (regla maestra 3).
	 *
	 * <p>Mismo reparto de tres operaciones y mismo motivo. La diferencia esta en quien lo consume:
	 * lo pide {@code encounter} a traves de {@code clinical.spi.CasoDirectory}, dentro de su
	 * transaccion de cierre, y <b>despues</b> de haber tomado el numerador de la historia. El orden
	 * es fijo y no es negociable: ver {@code SesionService#cerrar}.
	 */
	public interface CasoSesionNumeradorPort {

		/** Crea la fila si falta. Sin lanzar. Va en una transaccion aparte. */
		void crearSiFalta(long organizationId, long casoId);

		/** Incrementa el contador tomando el lock de fila. Serializa los cierres de ese caso. */
		void incrementar(long organizationId, long casoId);

		/** Lee el numero recien asignado, en la misma transaccion que lo incremento. */
		Integer leerUltimo(long organizationId, long casoId);
	}
}
