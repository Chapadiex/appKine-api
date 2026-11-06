# AKINE — Alta y edición de sede con ubicación

**05/11/2026** · ramas `akine-sede-ubicacion` (api) y `akine-web-sede-nueva-simple` (web, PR #160) ·
migración `V140` · contrato `0.160.0` · módulos M01/M03 (RF-M03-002, RF-M03-003) · pantalla
`/organizacion/sedes/nueva` y panel de edición de `/organizacion/sedes`.

Pedido del usuario: que una sede se identifique por dónde está (dirección estructurada, punto en
un mapa, contacto), que el alta no sea una carga administrativa (todo opcional salvo el nombre), que
los datos fiscales no se repitan sede por sede, y que la dirección sirva después para los pacientes
("Cómo llegar" en el turno y en los avisos).

## 1. Qué existía

- `Consultorio` (tabla `consultorio`) con `name`, `timezone`, `slot_minutes`, `legal_name`,
  `tax_id`, `address_line` (texto libre, "estructurarla es una decisión abierta" desde `V16`),
  `phone`, `contact_email`. Alta en un acto con primer box y horario general (A-8). Sin
  coordenadas, sin alias, sin visibilidad.
- `Organization` sin ningún dato fiscal.
- Asistente de tres pasos en el frontend (web #160 ya había pasado el horario a una semana con
  interruptores). Edición en un panel lateral con dos pestañas.
- Ningún cliente HTTP saliente en el backend; ninguna librería de mapas en el frontend; CSP de
  producción con `img-src 'self' data: blob:`.
- Avisos de turno al paciente que nombran la sede y nada más (`EmailTemplates.sede`).

## 2. Modelo

`V140__m03_sede_ubicacion_y_datos_fiscales.sql`:

| Tabla | Columnas nuevas | Para qué |
|---|---|---|
| `consultorio` | `alias` | Nombre visible, si difiere del nombre |
| `consultorio` | `pais` (alfa-2), `provincia`, `ciudad`, `calle`, `numero`, `codigo_postal`, `piso`, `referencias` | Dirección **física**, estructurada. `address_line` queda como **domicilio fiscal** |
| `consultorio` | `latitud`, `longitud` (`DECIMAL(9,6)`), `ubicacion_origen` (`GEOCODIFICADA` / `MANUAL`) | Punto en el mapa y de dónde salió. CHECK de rango y de par |
| `consultorio` | `agenda_habilitada` (def. 1), `direccion_publica` (def. 1) | Visibilidad: existir sin recibir turnos; mostrar o no la dirección al paciente |
| `consultorio` | `datos_fiscales_propios` (def. 0; backfill = 1 donde ya había datos) | Si factura con sus datos o con los de la organización |
| `organization` | `legal_name`, `tax_id`, `fiscal_address_line` | Datos fiscales que las sedes heredan |

Decisiones:

1. **La dirección se guarda en partes y el renglón se arma al leer** (`direccionFormateada`). Un
   `address_line` libre no se puede buscar por ciudad ni geocodificar por partes.
2. **"Pendiente de verificar" es derivado, no una columna**: dirección con partes y sin punto
   (`ubicacionPendiente`). Lo que sí se guarda es el origen del punto, porque "lo puso el buscador"
   y "lo arrastró alguien" se corrigen distinto.
3. **Datos fiscales: herencia por defecto.** `datosFiscalesEfectivos` en la respuesta resuelve de
   dónde salen (`SEDE` / `ORGANIZACION`). Teléfono y email son de contacto de la sede, no fiscales.
4. **CUIT**: si el valor *parece* un CUIT (`^\d{2}-?\d{8}-?\d$`) tiene que cerrar el dígito
   verificador (módulo 11). Si no lo parece, es una identificación fiscal libre (otro país).
5. **`agenda_habilitada` se hace cumplir en `scheduling`** al reservar: 409
   `consultorio-sin-agenda`. La sede llega a los demás módulos por `ConsultorioSnapshot`, que suma
   `agendaHabilitada`, `direccionPublica` (ya formateada, `null` si no es pública) y el punto.
6. **Los avisos de turno llevan la dirección y el enlace "Cómo llegar"** sólo si la sede la hace
   pública (`consultorioDireccion`, `consultorioLatitud`, `consultorioLongitud` en el payload).

## 3. Mapa y geocodificación: por qué no Google

No hay integración con Google Maps configurada (no hay clave, no hay cuenta de facturación) y la
consigna fue no simular una. Se eligió lo que funciona sin configurar y es compatible con el
proyecto:

| Pieza | Elección | Costo / condiciones |
|---|---|---|
| Mapa y marcador | **Leaflet 1.9** con teselas de **OpenStreetMap** | Gratis. Atribución obligatoria (la deja Leaflet). La política de teselas de OSM admite uso liviano; para volumen de producción corresponde un proveedor de teselas pago o propio. CSP: `img-src` suma `https://tile.openstreetmap.org` |
| Búsqueda de direcciones | **Nominatim** público, **a través del backend** (`GET /organizations/{orgId}/direcciones/busqueda?q=&pais=`) | Gratis. Política: máximo 1 consulta/segundo, identificar la aplicación (`User-Agent`), **prohibido el autocompletado**. Por eso la búsqueda sale con un botón ("Ubicar en el mapa"), el backend serializa a 1 req/s y cachea 24 h |
| Enlaces | `https://www.google.com/maps/search/?api=1&query=lat,lng` y `.../dir/?api=1&destination=lat,lng` | Gratis, sin clave: son URLs públicas |

El geocodificador es un puerto (`Geocodificador`) con un adaptador Nominatim y uno apagado
(`akine.geo.proveedor=NINGUNO` → 503 `geocodificador-no-disponible`). Cambiar a Google Geocoding
es un adaptador más, con su clave en `akine.geo.*`. **La pantalla no depende de que el buscador
responda**: el marcador se arrastra, las coordenadas se escriben a mano, y una dirección sin punto
se guarda como pendiente.

Leaflet se carga con `import()` dentro del componente `app-mapa-de-ubicacion` (`shared/`): no entra
en el bundle inicial, que está a 0,4 kB del tope.

## 4. Pantallas

- **Paso 1** en dos columnas (60/40): información general y dirección a la izquierda; mapa,
  coordenadas, enlaces y visibilidad a la derecha (`app-editor-de-ubicacion`, que proyecta lo de
  arriba y lo de abajo). Se apila bajo 60 rem.
- **Paso 2**: horario de atención (modelos "Lunes a viernes", "Lunes a sábado", "Toda la semana",
  "Con corte al mediodía", interruptor por día, hasta tres franjas por día, selector de cuartos de
  hora con entrada manual), primer box, y datos fiscales con el interruptor "Usar los datos fiscales
  de la organización" encendido por defecto.
- **Paso 3**: resumen con mapa compacto, visibilidad, horario dibujado, box y datos fiscales
  efectivos. Botón "Crear sede".
- **Edición**: el mismo editor de ubicación en la pestaña "Ubicación" del panel; visibilidad en
  "General"; herencia fiscal en "Datos fiscales". La organización edita sus datos fiscales en
  `/organizacion`.
- **Turno**: el detalle muestra la dirección de la sede y "Cómo llegar" si es pública
  (`SedeActivaStore` en `core/`, una lectura por contexto).

## 5. Lo que quedó afuera

- Autocompletado de direcciones: sólo con un proveedor que lo permita (Google Places). El puerto
  está; el adaptador no.
- Geocodificación inversa al arrastrar el marcador (no se rellena la dirección desde el punto).
- Verificar que la dirección escrita coincida con el punto elegido: se muestra el origen y se deja
  corregir; no hay comparación automática.
- `agenda_habilitada` no filtra la búsqueda de disponibilidad: sólo rechaza la reserva.
