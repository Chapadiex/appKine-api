package com.akine.person.domain.port;

import com.akine.person.domain.AdjuntoAdministrativo;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.AutorizacionAlerta;
import com.akine.person.domain.AutorizacionEvento;
import com.akine.person.domain.AutorizacionMovimiento;
import com.akine.person.domain.AutorizacionPersonaLock;
import com.akine.person.domain.TipoMovimientoAutorizacion;
import com.akine.person.domain.TipoOrigenMovimiento;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.CoberturaPersonaLock;
import com.akine.person.domain.OrdenMedica;
import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;

import java.time.Instant;
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
		 * Varias personas del tenant de una sola consulta, activas o no.
		 *
		 * <p>Es lo que evita el N+1 de las pantallas que muestran una lista de hechos sobre
		 * pacientes —la recepcion del dia, primero—. Los ids de otro tenant no vuelven: el filtro
		 * por organizacion va en la consulta y no despues.
		 */
		List<Persona> findAllByIdInAndOrganizationId(List<Long> ids, Long organizationId);

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

	/**
	 * Metadata de los adjuntos administrativos de una persona (AKINE-03.02, M25).
	 *
	 * <p><b>Toda firma empieza por {@code organizationId} y ninguna resuelve por id pelado.</b>
	 * Es la misma regla que los otros dos puertos: un {@code findById} sobre esta tabla seria la
	 * forma mas corta de leer el documento de identidad de un paciente de otro centro.
	 *
	 * <p>El {@code personaId} tambien viaja en las consultas de un adjunto suelto, y no es
	 * redundante: sin el, un adjunto de la persona A se podria descargar desde la ruta de la
	 * persona B de la misma organizacion, y la auditoria registraria la ficha equivocada.
	 */
	public interface AdjuntoRepositoryPort {

		AdjuntoAdministrativo save(AdjuntoAdministrativo adjunto);

		/**
		 * Persiste y sincroniza en el acto.
		 *
		 * <p>Mismo motivo que en los otros dos puertos: el choque del unique de checksum tiene que
		 * manifestarse aca, donde se lo sabe traducir a la respuesta idempotente que hace que
		 * reintentar una subida no deje dos filas.
		 */
		AdjuntoAdministrativo saveAndFlush(AdjuntoAdministrativo adjunto);

		Optional<AdjuntoAdministrativo> buscarDeLaPersona(
				Long organizationId, Long personaId, Long adjuntoId);

		/** El adjunto VIGENTE con ese contenido, si esa persona ya lo tiene. Idempotencia. */
		Optional<AdjuntoAdministrativo> buscarVigentePorChecksum(
				Long organizationId, Long personaId, String checksumSha256);

		/**
		 * Los adjuntos de una persona, mas nuevos primero.
		 *
		 * @param categoria     filtro por clasificacion, o {@code null} para no filtrar
		 * @param activoFiltro  {@code 1} solo vigentes, {@code 0} solo de baja, {@code -1} todos.
		 *                      Mismo codigo de tres estados que la busqueda del padron, por
		 *                      coherencia y porque un {@code Boolean} nullable en una query
		 *                      nativa se lee peor
		 */
		List<AdjuntoAdministrativo> listar(
				Long organizationId,
				Long personaId,
				String categoria,
				int activoFiltro,
				int offset,
				int limite);

		long contar(Long organizationId, Long personaId, String categoria, int activoFiltro);

		/**
		 * Cuantos adjuntos VIGENTES tiene la persona, agrupados por categoria.
		 *
		 * <p>Lo consume el Paciente 360, que muestra el conteo por categoria sin traerse la lista.
		 * Devuelve pares {@code [categoria, cantidad]} y no un tipo propio porque una proyeccion
		 * de dos columnas no justifica una interfaz de Spring Data mas.
		 */
		List<Object[]> contarVigentesPorCategoria(Long organizationId, Long personaId);
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

	/** Ordenes medicas de un paciente (M17, AKINE-03.06). */
	public interface OrdenMedicaRepositoryPort {

		OrdenMedica save(OrdenMedica orden);

		OrdenMedica saveAndFlush(OrdenMedica orden);

		Optional<OrdenMedica> findByIdAndOrganizationIdAndPersonaId(
				Long id, Long organizationId, Long personaId);

		/** Todas las ordenes del paciente, mas nuevas primero. Incluye las dadas de baja. */
		List<OrdenMedica> historial(Long organizationId, Long personaId);

		/** Solo las activas. Es el conjunto que resuelve el requisito de orden del convenio. */
		List<OrdenMedica> activasDe(Long organizationId, Long personaId);
	}

	/** Autorizaciones de un paciente (M17, AKINE-03.06). */
	public interface AutorizacionRepositoryPort {

		Autorizacion save(Autorizacion autorizacion);

		Autorizacion saveAndFlush(Autorizacion autorizacion);

		Optional<Autorizacion> findByIdAndOrganizationIdAndPersonaId(
				Long id, Long organizationId, Long personaId);

		/**
		 * Una autorizacion del tenant por id, sin exigir la persona.
		 *
		 * <p>Es la excepcion a la forma de las demas y esta verificada individualmente, como el
		 * encabezado de este archivo obliga: <b>lleva {@code organizationId}</b>, que es lo que
		 * impide leer la autorizacion de otro centro. El {@code personaId} no viaja porque las
		 * rutas del consumo —{@code /autorizaciones/{id}/saldo}, {@code /movimientos},
		 * {@code /reversiones}— no lo tienen: son operaciones sobre la autorizacion, no sobre la
		 * ficha, y agregarlo a la ruta solo para repetirlo aca haria que dos ids tuvieran que
		 * coincidir sin que eso proteja nada nuevo.
		 */
		Optional<Autorizacion> findByIdAndOrganizationId(Long id, Long organizationId);

		/** Todas, mas nuevas primero. Incluye vencidas, rechazadas y dadas de baja. */
		List<Autorizacion> historial(Long organizationId, Long personaId);

		/**
		 * Las APROBADAS y activas de un PACIENTE, sin filtrar por cobertura ni practica.
		 *
		 * <p>Es lo que resuelve {@code GET /personas/{id}/autorizaciones/elegibles} y lo que el
		 * observador del cierre recorre para elegir a cual descontarle. <b>No filtra por practica
		 * porque no puede</b>: una sesion declara su <b>oferta</b> y la autorizacion es por
		 * <b>practica</b>, y no existe ninguna tabla puente entre las dos —V24 la dejo afuera a
		 * proposito—. La eleccion la hace el servicio con la vigencia y el saldo. El limite esta
		 * declarado en el diseño de la etapa y se unifica en 06.04.
		 */
		List<Autorizacion> aprobadasDePersona(Long organizationId, Long personaId);

		/**
		 * Las APROBADAS y activas de una cobertura y una practica.
		 *
		 * <p>Es el conjunto contra el que se valida el solapamiento y el que resuelve la
		 * elegibilidad. Filtra por estado en la base y no en memoria porque una PENDIENTE no
		 * participa de ninguna de las dos preguntas, y traerlas para descartarlas seria leer
		 * bajo el lock mas filas de las que la regla mira.
		 */
		List<Autorizacion> aprobadasDe(Long organizationId, Long coberturaId, Long practicaId);

		/**
		 * Descuenta {@code cantidad} del saldo, <b>si alcanza</b> (RF-M17-004, RN-M17-002).
		 *
		 * <p><b>Es un UPDATE condicional y no un lock, y esa es toda la idea:</b>
		 * {@code SET cantidad_consumida = cantidad_consumida + :cantidad WHERE cantidad_autorizada
		 * - cantidad_consumida >= :cantidad}. Es atomico, no necesita leer antes —que es donde se
		 * cuela la ventana entre lectura y escritura— y no puede dejar el saldo en negativo aunque
		 * dos cierres lleguen juntos. Sin lock, y por lo tanto sin deadlock posible.
		 *
		 * <p>Es exactamente lo que 07.02 hizo para imputar un cobro, y resuelve el caso borde que
		 * el plan nombra: <b>ultima unidad concurrente</b>. Dos sesiones peleando por la ultima
		 * unidad: una gana, la otra ve cero filas.
		 *
		 * <p>Una autorizacion <b>sin tope declarado</b> ({@code cantidad_autorizada IS NULL}) se
		 * descuenta siempre: no hay nada que agotar. El contador sube igual, porque la pregunta
		 * "cuantas sesiones se atendieron contra esta autorizacion" tiene respuesta aunque no haya
		 * limite.
		 *
		 * @return filas afectadas. <b>Cero significa que no hay saldo</b>, y es un desenlace
		 *         legitimo que el llamador traduce segun quien pregunte: 409 para un acto humano,
		 *         un simple registro para el cierre de sesion, que no puede fallar por esto
		 */
		int descontarSaldo(long organizationId, long autorizacionId, int cantidad);

		/**
		 * Devuelve {@code cantidad} al saldo. Es el {@code UPDATE} inverso de la reversion.
		 *
		 * <p>Tambien condicional —{@code WHERE cantidad_consumida >= :cantidad}—: un consumo
		 * negativo es tan imposible como un saldo negativo, y por el mismo motivo se lo impide la
		 * base y no un {@code if}. Cero filas significa que el ledger y la columna divergieron, y
		 * el llamador lo trata como el error de coherencia que es.
		 */
		int devolverSaldo(long organizationId, long autorizacionId, int cantidad);
	}

	/**
	 * El ledger de movimientos de saldo (M17, AKINE-04.05).
	 *
	 * <p><b>Append-only: este puerto NO declara {@code update} ni {@code delete}, y la ausencia es
	 * el contrato.</b> Un ledger que se puede editar no es un ledger. Mismo diseño que
	 * {@code turno_evento}, {@code caso_evento} y {@code plan_evento}, y la misma regla maestra 10.
	 * Corregir un consumo se hace con una fila de compensacion, no borrando la original.
	 */
	public interface AutorizacionMovimientoRepositoryPort {

		AutorizacionMovimiento save(AutorizacionMovimiento movimiento);

		/**
		 * El movimiento que ya existe para ese hecho, si existe. <b>Es la idempotencia.</b>
		 *
		 * <p>Los cinco parametros son exactamente el unique
		 * {@code uk_autorizacion_movimiento_origen}. Se consulta ANTES de insertar para poder
		 * responder con la fila que ya esta en vez de chocar: un reintento del mismo cierre tiene
		 * que ser inocuo, no un 409.
		 *
		 * <p><b>El pre-chequeo achica la ventana, no la cierra</b>, y por eso el unique sigue
		 * siendo quien hace cumplir la regla.
		 */
		Optional<AutorizacionMovimiento> buscarPorOrigen(
				Long organizationId,
				Long autorizacionId,
				TipoMovimientoAutorizacion tipo,
				TipoOrigenMovimiento tipoOrigen,
				Long referenciaOrigen);

		/** Un movimiento de ESA autorizacion y ESE tenant. Nunca por id pelado. */
		Optional<AutorizacionMovimiento> buscarDeLaAutorizacion(
				Long organizationId, Long autorizacionId, Long movimientoId);

		/**
		 * El ledger de una autorizacion, del mas viejo al mas nuevo.
		 *
		 * <p>En ese orden porque esto no es una bandeja sino una linea de tiempo, y una linea de
		 * tiempo se lee en el orden en que ocurrio. Mismo criterio que {@code plan_evento}.
		 */
		List<AutorizacionMovimiento> listarDeAutorizacion(
				Long organizationId, Long autorizacionId);

		/**
		 * Todos los movimientos de un hecho de origen, de CUALQUIER autorizacion (AKINE C-4).
		 *
		 * <p>Es la pregunta "que dejo esta sesion en el ledger", y sirve para dos cosas: que el
		 * re-disparo de un cierre no consuma otra autorizacion por la misma practica (DP-12), y
		 * saber que consumos alertar cuando se anula la deuda de la sesion (DP-13).
		 */
		List<AutorizacionMovimiento> listarDeOrigen(
				Long organizationId, TipoOrigenMovimiento tipoOrigen, Long referenciaOrigen);
	}

	/**
	 * Historial append-only de una autorizacion (DP-23, AKINE B-4). Sin modificacion ni borrado.
	 *
	 * <p>Las dos consultas llevan {@code organizationId}: verificadas una por una, como pide el
	 * encabezado de este archivo.
	 */
	public interface AutorizacionEventoRepositoryPort {

		AutorizacionEvento registrar(AutorizacionEvento evento);

		/** Una pagina del historial, del hecho mas viejo al mas nuevo. */
		List<AutorizacionEvento> pagina(
				long organizationId, long autorizacionId, int offset, int limite);

		long contar(long organizationId, long autorizacionId);
	}

	/**
	 * Alertas sobre autorizaciones que nacen de hechos de otros modulos (DP-13, AKINE C-4).
	 *
	 * <p><b>Ni {@code save} ni {@code delete}.</b> Se inserta con un {@code INSERT ... ON
	 * DUPLICATE KEY UPDATE} —una alerta por consumo, idempotente y sin choque ante dos
	 * anulaciones concurrentes— y se resuelve con un {@code UPDATE} condicional. Una alerta no se
	 * borra: se resuelve.
	 */
	public interface AutorizacionAlertaRepositoryPort {

		/**
		 * Registra la alerta si ese consumo todavia no tiene una del mismo tipo. Si ya la tiene,
		 * no hace nada y no lanza.
		 *
		 * <p>No devuelve filas afectadas a proposito: con el {@code CLIENT_FOUND_ROWS} que
		 * Connector/J activa por defecto, el no-op del {@code ON DUPLICATE KEY UPDATE} informa 1
		 * igual que una insercion, asi que el numero no distingue nada.
		 */
		@SuppressWarnings("java:S107")
		void registrarSiFalta(
				long organizationId,
				long autorizacionId,
				long personaId,
				long movimientoId,
				String tipo,
				long sesionId,
				long obligacionId,
				String motivoOrigen,
				Instant generadaEn,
				Long generadaPor);

		/** Marca resuelta la alerta PENDIENTE de ese consumo, si la hay. */
		int resolverDelMovimiento(
				long organizationId,
				long movimientoId,
				String resolucion,
				Instant resueltaEn,
				Long resueltaPor);

		/** Las alertas de una autorizacion, de la mas vieja a la mas nueva. */
		List<AutorizacionAlerta> listarDeAutorizacion(long organizationId, long autorizacionId);
	}

	/**
	 * La fila-lock por persona que serializa las APROBACIONES. <b>No guarda estado.</b>
	 *
	 * <p>Separada de {@code CoberturaPersonaLockRepositoryPort} aunque tenga la misma forma:
	 * protegen invariantes distintas, y compartir la fila ataria dos reglas independientes al
	 * mismo punto de contencion.
	 */
	public interface AutorizacionPersonaLockRepositoryPort {

		/** Lock exclusivo sobre la fila de esa persona. */
		Optional<AutorizacionPersonaLock> lockByScope(long organizationId, long personaId);

		/** Crea la fila si falta, sin lanzar nunca. Ver el javadoc de la implementacion. */
		void crearSiFalta(long organizationId, long personaId);
	}
}
