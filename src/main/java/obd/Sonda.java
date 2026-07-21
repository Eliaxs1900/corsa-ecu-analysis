package obd;

import java.io.IOException;

/**
 * Secuencia de detección de protocolo pensada para un Corsa C 1.7 diésel (2003):
 * anterior a la obligación EOBD en diésel (2004), así que puede responder al
 * modo genérico, responder solo con init forzado por K-line, o no responder.
 */
public final class Sonda {

    public record Resultado(String protocolo, String detalle, boolean eobdGenerico) {}

    private Sonda() {}

    /** Prepara el ELM327: reset, sin eco, cabeceras visibles, timeout ISO largo. */
    public static String inicializarAdaptador(Elm327 elm) throws IOException {
        String id = elm.send("ATZ", Elm327.TIMEOUT_INIT_MS);
        elm.send("ATE0");        // sin eco
        elm.send("ATL0");        // sin linefeeds extra
        elm.send("ATH1");        // mostrar cabeceras: clave para saber quién responde
        elm.send("ATST C8");     // timeout de respuesta ISO ~800 ms (ECUs viejas son lentas)
        return id;
    }

    /**
     * Prueba protocolos en orden de probabilidad para este coche:
     * auto → KWP rápido (5) → KWP lento 5-baudios (4) → ISO 9141-2 (3).
     */
    public static Resultado detectar(Elm327 elm) throws IOException {
        String[][] intentos = {
                {"0", "autodetección del ELM327"},
                {"5", "ISO 14230-4 KWP (init rápido)"},
                {"4", "ISO 14230-4 KWP (init lento 5 baudios)"},
                {"3", "ISO 9141-2"},
        };
        StringBuilder log = new StringBuilder();
        for (String[] intento : intentos) {
            elm.send("ATSP " + intento[0]);
            String resp;
            try {
                resp = elm.send("0100", Elm327.TIMEOUT_INIT_MS);
            } catch (IOException e) {
                log.append("ATSP ").append(intento[0]).append(": ").append(e.getMessage()).append('\n');
                continue;
            }
            log.append("ATSP ").append(intento[0]).append(": ").append(resp.replace('\n', ' ')).append('\n');
            if (respondeAlModo1(resp)) {
                String num = elm.send("ATDPN"); // protocolo realmente negociado
                return new Resultado(intento[1] + " [ATDPN=" + num + "]",
                        "El coche responde a EOBD genérico. PIDs soportados: " + resp, true);
            }
        }
        return new Resultado("ninguno (modo genérico)",
                "Sin respuesta EOBD genérica. Siguiente paso: KWP crudo con "
                        + "direccionamiento Opel (ATSH 81 11 F1 + ATSW 00 + StartCommunication).\n"
                        + "Registro de intentos:\n" + log, false);
    }

    /** ¿Contiene la respuesta un "41 00 ..." válido (respuesta positiva al modo 01 PID 00)? */
    static boolean respondeAlModo1(String resp) {
        String plano = resp.replace(" ", "").replace("\n", "").toUpperCase();
        return plano.contains("4100") && !plano.contains("UNABLE") && !plano.contains("ERROR");
    }
}
