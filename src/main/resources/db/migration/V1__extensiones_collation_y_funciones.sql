-- =====================================================================================
-- AKINE — extensiones, collation y funciones compartidas
--
-- Baseline PostgreSQL consolidado (11/11/2026). Reemplaza las 145 migraciones de MySQL, que quedan
-- como archivo historico en ops/migracion-mysql/mysql-historico.
-- =====================================================================================

-- pgvector: tipo vector y operadores de distancia para la biblioteca (M30). Crearla exige
-- superusuario, asi que en un servidor nuevo la crea ops/postgres/init (o el DBA) antes de Flyway;
-- con IF NOT EXISTS esta linea no exige nada si ya existe.
CREATE EXTENSION IF NOT EXISTS vector;

-- unaccent: la busqueda de texto de la biblioteca ignora tildes, como la ignoraba el FULLTEXT de
-- MySQL con utf8mb4_0900_ai_ci. Es una extension "trusted": la crea el duenio de la base.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- -------------------------------------------------------------------------------------
-- akine_ci: comparacion sin mayusculas ni tildes.
--
-- MySQL comparaba todo texto con utf8mb4_0900_ai_ci: 'Álvarez' = 'alvarez', y un UNIQUE de correo
-- o de codigo no admitia dos valores que solo difieren en mayusculas. PostgreSQL compara byte a byte
-- por defecto. Para no cambiar ninguna regla de negocio, cada columna de texto declara esta
-- collation ICU no determinista de nivel 1 (base de la letra), que da la misma igualdad, el mismo
-- orden y los mismos UNIQUE que tenia MySQL. LIKE sobre ella existe desde PostgreSQL 18.
--
-- Lo que NO admite una collation no determinista: expresiones regulares (~), y busquedas de
-- subcadena como position()/strpos()/replace(). Donde hagan falta se comparan con COLLATE "C".
-- -------------------------------------------------------------------------------------
CREATE COLLATION IF NOT EXISTS akine_ci (provider = icu, locale = 'und-u-ks-level1', deterministic = false);

-- -------------------------------------------------------------------------------------
-- updated_at automatico: el equivalente de ON UPDATE CURRENT_TIMESTAMP(6) de MySQL.
--
-- MySQL lo movia solo cuando la fila cambiaba y la sentencia no fijaba la columna. Aca igual: si el
-- UPDATE ya trae un updated_at distinto del anterior, se respeta; si no, se pone el instante actual.
-- Los UPDATE nativos de los numeradores dependen de esto (no tocan updated_at a mano).
-- -------------------------------------------------------------------------------------
CREATE FUNCTION akine_marcar_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW IS DISTINCT FROM OLD AND NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
        NEW.updated_at := CURRENT_TIMESTAMP;
    END IF;
    RETURN NEW;
END;
$$;

-- -------------------------------------------------------------------------------------
-- audit_event es append-only en la BASE, no solo en el codigo (RN-M24-001, AKINE-01.03).
-- -------------------------------------------------------------------------------------
CREATE FUNCTION akine_auditoria_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'audit_event es append-only: RN-M24-001 prohibe modificar la auditoria'
            USING ERRCODE = '45000';
    END IF;
    RAISE EXCEPTION 'audit_event es append-only: RN-M24-001 prohibe borrar la auditoria'
        USING ERRCODE = '45000';
END;
$$;
