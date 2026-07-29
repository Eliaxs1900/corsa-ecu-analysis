package io.github.eliaxs1900.corsaecuanalysis.core;

import java.io.IOException;

/**
 * Canal de texto hacia un adaptador ELM327: se le manda un comando y devuelve la
 * respuesta acumulada hasta el prompt {@code '>'}.
 *
 * <p>Es la única pieza que cambia entre plataformas — en escritorio va sobre un
 * puerto serie (jSerialComm) y en Android sobre un socket Bluetooth RFCOMM —,
 * así que todo lo demás del paquete {@code core} (protocolo KWP2000, decodificación
 * del bloque de datos y catálogo de averías) se comparte tal cual entre ambas.
 */
public interface Transport {

    /** Timeout normal para comandos AT y peticiones ya en sesión. */
    long TIMEOUT_CMD_MS = 5_000;
    /** Timeout largo: ATZ, init de bus y primeras peticiones (la ECU es lenta). */
    long TIMEOUT_INIT_MS = 20_000;

    /** Envía un comando y devuelve la respuesta limpia (sin eco ni líneas vacías). */
    String send(String cmd, long timeoutMs) throws IOException;

    default String send(String cmd) throws IOException {
        return send(cmd, TIMEOUT_CMD_MS);
    }
}
