package io.github.eliaxs1900.corsaecuanalysis.desktop;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import io.github.eliaxs1900.corsaecuanalysis.core.Transport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Transporte serie hacia un adaptador ELM327 (Vgate iCar2 BT3.0 emparejado
 * como puerto COM SPP). Protocolo de texto: comando + CR, respuesta hasta
 * el prompt '>'.
 */
public final class Elm327 implements Transport, AutoCloseable {

    /** Timeout por defecto para comandos AT y peticiones OBD ya inicializadas. */
    public static final long TIMEOUT_CMD_MS = 5_000;
    /** Timeout largo: ATZ y primeras peticiones tras ATSP (el init lento a 5 baudios tarda varios segundos). */
    public static final long TIMEOUT_INIT_MS = 20_000;

    private final SerialPort port;
    private volatile boolean traza;
    private final Thread cierreAlSalir;

    private Elm327(SerialPort port) {
        this.port = port;
        // Si el proceso termina sin pasar por close() (Ctrl+C, cierre de ventana,
        // excepción no capturada), Windows deja el enlace RFCOMM del adaptador
        // Bluetooth "pegado" y el puerto queda inutilizable hasta reiniciar el
        // Bluetooth. Este gancho garantiza que siempre se cierre.
        this.cierreAlSalir = new Thread(this::cerrarPuerto, "cierre-puerto-serie");
        Runtime.getRuntime().addShutdownHook(cierreAlSalir);
    }

    public static List<String> puertosDisponibles() {
        List<String> out = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            out.add(p.getSystemPortName() + "  (" + p.getDescriptivePortName() + ")");
        }
        return out;
    }

    public static Elm327 abrir(String nombrePuerto) throws IOException {
        SerialPort p = SerialPort.getCommPort(nombrePuerto);
        // Sobre SPP el baudrate es virtual, pero jSerialComm exige fijarlo.
        p.setComPortParameters(38_400, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        // Timeout de escritura imprescindible: con COM Bluetooth la escritura
        // bloquea indefinidamente si el adaptador no está encendido/al alcance.
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 200, 5_000);
        if (!p.openPort()) {
            throw new IOException(explicarFallo(nombrePuerto, p.getLastErrorCode()));
        }
        return new Elm327(p);
    }

    /**
     * Traduce el código de error de Windows a una causa accionable. El más
     * habitual con un adaptador SPP es el 5 (acceso denegado): estos adaptadores
     * solo admiten UNA conexión a la vez, así que si el móvil está conectado el
     * PC no puede abrir el puerto.
     */
    private static String explicarFallo(String puerto, int codigo) {
        String causa = switch (codigo) {
            case 5 -> "acceso denegado. O bien el adaptador ya está conectado a otro equipo "
                    + "(solo admite uno a la vez), o Windows tiene un enlace Bluetooth colgado. "
                    + "Cierra la app del móvil; si sigue igual, apaga y enciende el Bluetooth de Windows.";
            case 121 -> "el adaptador no responde (tiempo de espera agotado). Suele ser por "
                    + "DISTANCIA: acércate al coche. Comprueba también que el adaptador está "
                    + "enchufado al conector OBD y con su LED encendido.";
            case 2, 1167 -> "el adaptador no está accesible. Comprueba que está enchufado al "
                    + "conector OBD y dentro del alcance del Bluetooth.";
            default -> "¿es este el COM saliente y está emparejado el adaptador?";
        };
        return "No se pudo abrir " + puerto + " (error " + codigo + "): " + causa;
    }

    /** Activa/desactiva el volcado de la conversación cruda por consola. */
    public void setTraza(boolean traza) {
        this.traza = traza;
    }

    @Override
    public String send(String cmd) throws IOException {
        return send(cmd, TIMEOUT_CMD_MS);
    }

    /**
     * Envía un comando y acumula la respuesta hasta el prompt '>'.
     * Devuelve el texto limpio (sin eco del comando, sin líneas vacías).
     */
    @Override
    public String send(String cmd, long timeoutMs) throws IOException {
        if (traza) System.out.println("  >> " + cmd);
        byte[] out = (cmd + "\r").getBytes(StandardCharsets.US_ASCII);
        if (port.writeBytes(out, out.length) < out.length) {
            throw new IOException("No se pudo escribir en el puerto: el adaptador no responde "
                    + "(¿tiene alimentación? ¿es este el COM saliente?)");
        }
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[256];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int n = port.readBytes(buf, buf.length);
            if (n < 0) throw new IOException("Puerto cerrado durante la lectura");
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == '>') {
                    String resp = limpiar(sb.toString(), cmd);
                    if (traza) System.out.println("  << " + resp.replace("\n", "\n  << "));
                    return resp;
                }
                if (c != 0) sb.append(c);
            }
        }
        throw new IOException("Timeout tras " + timeoutMs + " ms esperando respuesta a '" + cmd
                + "'" + (sb.isEmpty() ? "" : " (parcial: " + limpiar(sb.toString(), cmd) + ")"));
    }

    private static String limpiar(String crudo, String cmd) {
        List<String> lineas = new ArrayList<>();
        for (String l : crudo.split("\r|\n")) {
            String t = l.trim();
            if (t.isEmpty() || t.equalsIgnoreCase(cmd)) continue; // descarta eco y vacías
            lineas.add(t);
        }
        return String.join("\n", lineas);
    }

    @Override
    public void close() {
        cerrarPuerto();
        // Ya no hace falta el gancho de apagado.
        try {
            Runtime.getRuntime().removeShutdownHook(cierreAlSalir);
        } catch (IllegalStateException ignored) {
            // la JVM ya se está apagando: el propio gancho hará el cierre
        }
    }

    /** Cierre idempotente: puede llamarse desde close() y desde el gancho de apagado. */
    private synchronized void cerrarPuerto() {
        if (port.isOpen()) port.closePort();
    }
}
