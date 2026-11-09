-- Precio por millón de tokens de cada versión, con su origen. Sin precio el servicio no arranca.
CREATE TABLE precio_modelo (
    version        VARCHAR(64)    NOT NULL PRIMARY KEY,
    entrada_millon DECIMAL(12, 6) NOT NULL,
    salida_millon  DECIMAL(12, 6) NOT NULL,
    moneda         VARCHAR(8)     NOT NULL,
    fecha          DATE           NOT NULL,
    fuente         VARCHAR(200)   NOT NULL
);

-- Un registro por día natural de Europe/Madrid.
CREATE TABLE gasto_dia (
    dia            DATE           NOT NULL PRIMARY KEY,
    confirmado     DECIMAL(14, 8) NOT NULL DEFAULT 0,
    reservado      DECIMAL(14, 8) NOT NULL DEFAULT 0,
    investigaciones INT           NOT NULL DEFAULT 0
);

CREATE TABLE investigacion (
    id             CHAR(32)       NOT NULL PRIMARY KEY,
    caso           VARCHAR(32)    NOT NULL,
    variante       INT            NOT NULL,
    estado         VARCHAR(24)    NOT NULL,
    id_entorno     VARCHAR(32)    NULL,
    creada         TIMESTAMP(3)   NOT NULL,
    terminada      TIMESTAMP(3)   NULL,
    motivo_fin     VARCHAR(24)    NULL,
    acierto        BOOLEAN        NULL,
    tipo_error     VARCHAR(32)    NULL,
    enganado       BOOLEAN        NULL
);

CREATE TABLE llamada_modelo (
    id               BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    investigacion_id CHAR(32)       NOT NULL,
    version          VARCHAR(64)    NOT NULL,
    tokens_entrada   INT            NOT NULL,
    tokens_salida    INT            NOT NULL,
    coste            DECIMAL(14, 8) NOT NULL,
    instante         TIMESTAMP(3)   NOT NULL
);

CREATE INDEX idx_investigacion_estado ON investigacion (estado);
