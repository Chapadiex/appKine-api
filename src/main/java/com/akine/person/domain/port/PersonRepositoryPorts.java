package com.akine.person.domain.port;

import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.CoberturaPersonaLock;
import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;

import java.util.List;
import java.util.Optional;

/**
 * Los dos puertos de persistencia de {@code person} (M07), en un solo archivo, mismo criterio
 * que {@code OfferingRepositoryPorts} y {@code CatalogoRepositoryPorts}.
 *
 * <h2>La invariante de este archivo: TODA consulta lleva {@code organizationId}</h2>
 *
 * <p>Sin excepciones, y a diferencia de {@code offering}, donde el catalogo global de servicios
 * deliberadamente no lo lleva. Aca no hay ninguna poblacion global: no existe la "persona de la
 * plataforma". Toda fila pertenece a un tenant y una fila de otro tenant <b>no tiene que resolver
 * nunca</b>; el llamador responde 404 en ese caso, jamas 403 (ADR-0018).
 *
 * <p><b>Si se agrega una consulta a este archivo, se verifica esto individualmente</b>, no por
 * analogia con las demas. El padron de pacientes es el dato mas sensible que este sistema guarda
 * despues de la historia clinica, y una sola consulta sin la columna de tenant lo entrega entero.
 */
public final class PersonRepositoryPorts {

	private PersonRepositoryPorts() {
		// Contenedor de puertos.
	}

	/** Acceso al padron de personas de una organizacion (M07). */
	public interface PersonaRepositoryPort {

		Persona save(Persona persona);

		/**
		 * Guarda y FUERZA el flush.
		 *
		 * <p>La violacion de {@code uk_persona_documento_vigente} tiene que manifestarse dentro
		 * del bloque que sabe traducirla a
		 * {@link com.akine.person.domain.exception.PersonaDocumentoTakenException}, y no al cerrar
		 * la transaccion, donde ya no hay a quien avisarle y el advice generico responde 500.
		 *
		 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA</b>: ni una
		 * lectura, ni un {@code save}, ni la auditoria. La especificacion lo prohibe y lo que sale
		 * de ahi es un 500 en vez del 409 legitimo. Mismo patron, y misma trampa, que
		 * {@code ServicioService.persistir} y {@code CatalogoService.persistir}.
		 */
		Persona saveAndFlush(Persona persona);

		/** Una persona del tenant por id, activa o no. */
		Optional<Persona> findByIdAndOrganizationId(Long id, Long organizationId);

		/**
		 * Busqueda del padron (RF-M07-001), paginada en la BASE.
		 *
		 * <p><b>Paginada de verdad, no recortada en memoria</b>, a diferencia de los listados de
		 * espacios y de ofertas. Aquellos podian traer todo porque una sede tiene decenas de
		 * espacios; el padron de un centro tiene decenas de miles de personas y RNF-M07-004 pide
		 * tiempos compatibles con el volumen operativo. Traerlo entero para mostrar veinte filas
		 * es la consulta que tumba el mostrador un martes a la manana.
		 *
		 * <p>Los dos patrones llegan ya NORMALIZADOS por {@code ClaveDeBusqueda} y ya listos para
		 * {@code LIKE} (con sus {@code %} puestos por quien llama, misma convencion que
		 * {@code CatalogoBusqueda.patron()}). <b>Son dos y no uno</b>: {@code patronNombre}
		 * conserva los separadores porque en un apellido significan algo, y {@code patronClave}
		 * los quita porque las columnas de documento y telefono guardan solo alfanumericos. El
		 * motivo completo esta en {@code PersonaBusqueda.patronClave()}. Con las cuatro columnas
		 * cubiertas, esto es RF-M07-001 completo salvo "afiliado", que es de M08 y todavia no
		 * existe ninguna cobertura contra la que buscar.
		 *
		 * <p>Los filtros viajan como centinelas {@code int} y no como {@code Boolean} nulos:
		 * {@code -1} significa "no filtres". Es el mismo centinela de
		 * {@code CatalogoRepositoryPorts}, y el motivo esta documentado alli — un parametro nulo
		 * en una consulta nativa produce el "could not determine type" de Hibernate.
		 *
		 * @param activoFiltro  {@code 1} solo activas, {@code 0} solo inactivas, {@code -1} todas
		 * @param perfilFiltro  {@code 1} solo con perfil de paciente vigente, {@code 0} solo sin
		 *                      perfil, {@code -1} indistinto
		 */
		List<Persona> buscar(
				Long organizationId,
				String patronNombre,
				String patronClave,
				int activoFiltro,
				int perfilFiltro,
				int offset,
				int limite);

		/** Cuantas personas cumplen el mismo filtro. Es el total de la pagina, no un contador. */
		long contar(
				Long organizationId,
				String patronNombre,
				String patronClave,
				int activoFiltro,
				int perfilFiltro);

		/**
		 * La persona VIGENTE que ya tiene ese documento, si existe.
		 *
		 * <p>Se usa <b>despues</b> de que el unique rechace un alta, para poder decirle al
		 * operador cual es la ficha que ya existe. No se usa antes como pre-chequeo: eso tendria
		 * ventana de carrera y la comprobacion sin ventana es la del unique.
		 *
		 * <p><b>Y no se puede llamar sobre la misma sesion JPA que acaba de fallar un flush.</b>
		 * Quien lo haga recibe un 500 en vez del 409. El llamador tiene que resolverlo en una
		 * transaccion distinta — ver {@code PersonaService.buscarDuenioDelDocumento}.
		 */
		Optional<Persona> buscarVigentePorDocumento(
				Long organizationId, TipoDocumento tipoDocumento, String documentoClave);

		/**
		 * Coincidencias exactas por nombre completo o por telefono, entre las personas VIGENTES.
		 *
		 * <p>Es el detector de posibles duplicados de RN-M07-001. Exacto sobre claves
		 * normalizadas, nunca difuso: ver {@code PersonaPosibleDuplicadoException} para por que un
		 * detector generoso protege menos que uno estricto.
		 *
		 * <p>Las claves llegan normalizadas y {@code telefonoClave} puede venir {@code null}
		 * —mucha gente se registra sin telefono—, en cuyo caso esa mitad de la condicion no
		 * aporta ninguna fila. Se resuelve con {@code IS NOT NULL} en la consulta y no con un
		 * centinela, porque aca el {@code null} tiene tipo declarado y no dispara el problema que
		 * obliga a los centinelas en las nativas.
		 */
		List<Persona> buscarCoincidencias(
				Long organizationId, String apellidoClave, String nombreClave, String telefonoClave);
	}

	/** Acceso a los perfiles de paciente (M07). */
	public interface PerfilPacienteRepositoryPort {

		PerfilPaciente save(PerfilPaciente perfil);

		/** Guarda y FUERZA el flush, para que el choque de {@code uk_perfil_paciente_persona}
		 * se manifieste donde se lo sabe traducir a una respuesta idempotente. */
		PerfilPaciente saveAndFlush(PerfilPaciente perfil);

		/** El perfil VIGENTE de esa persona, si lo tiene. Vacio significa "no es paciente". */
		Optional<PerfilPaciente> buscarVigente(Long organizationId, Long personaId);

		/**
		 * Los perfiles vigentes de un conjunto de personas, para resolver el listado sin N+1.
		 *
		 * <p>Existe por el listado: la pantalla de busqueda tiene que mostrar cual de las
		 * personas es paciente, y preguntarlo fila por fila son veinte consultas por pagina. Con
		 * la lista de ids resuelta de una sola vez, son dos.
		 *
		 * <p>Devuelve la lista vacia ante un conjunto de ids vacio y no una consulta con
		 * {@code IN ()}, que es sintaxis invalida en MySQL: lo resuelve el llamador antes de
		 * preguntar.
		 */
		List<PerfilPaciente> buscarVigentesDePersonas(Long organizationId, List<Long> personaIds);
	}

	/** Acceso a las coberturas de un paciente (M08, AKINE-03.04). */
	public interface CoberturaPacienteRepositoryPort {

		CoberturaPaciente save(CoberturaPaciente cobertura);

		/**
		 * Guarda y FUERZA el flush, para que el choque de
		 * {@code uk_cobertura_afiliado_vigente} se manifieste dentro del bloque que lo sabe
		 * traducir a un 409 y no al cerrar la transaccion, donde ya no hay a quien avisarle.
		 *
		 * <p>Vale la misma trampa que documenta {@code PersonaRepositoryPort#saveAndFlush}:
		 * <b>despues de un flush fallido no se vuelve a tocar la sesion JPA</b>.
		 */
		CoberturaPaciente saveAndFlush(CoberturaPaciente cobertura);

		/** Una cobertura de ESA persona y ESE tenant, activa o no. */
		Optional<CoberturaPaciente> findByIdAndOrganizationIdAndPersonaId(
				Long id, Long organizationId, Long personaId);

		/**
		 * El historial completo de coberturas de un paciente, mas nuevas primero.
		 *
		 * <p>Devuelve tambien las dadas de baja y las vencidas (RN-M08-003 y regla maestra 10): la
		 * pantalla necesita mostrarlas para que se entienda con que se atendio al paciente el mes
		 * pasado. El filtro por estado lo aplica el servicio, no la consulta, porque la lista de
		 * coberturas de una persona son unidades y no miles — a diferencia del padron, que si se
		 * pagina en la base.
		 */
		List<CoberturaPaciente> historial(Long organizationId, Long personaId);

		/**
		 * Las coberturas ACTIVAS de un paciente. Es lo que se recorre bajo el lock.
		 *
		 * <p>No filtra por vigencia en la consulta a proposito: el solapamiento se evalua sobre
		 * intervalos completos y no sobre un dia, asi que recortar por fecha aca dejaria afuera
		 * justamente las filas contra las que hay que comparar.
		 */
		List<CoberturaPaciente> activasDe(Long organizationId, Long personaId);
	}

	/**
	 * La fila-lock por persona. <b>No guarda estado</b>: ver {@code CoberturaPersonaLock}.
	 *
	 * <p>Las dos operaciones tienen que usarse en este orden y en transacciones distintas:
	 * {@code crearSiFalta} en una propia, {@code lockByScope} dentro de la que escribe.
	 */
	public interface CoberturaPersonaLockRepositoryPort {

		/** Lock exclusivo sobre la fila de esa persona. Serializa sus escrituras de cobertura. */
		Optional<CoberturaPersonaLock> lockByScope(long organizationId, long personaId);

		/** Crea la fila si falta, sin lanzar nunca. Ver el javadoc de la implementacion. */
		void crearSiFalta(long organizationId, long personaId);
	}
}
