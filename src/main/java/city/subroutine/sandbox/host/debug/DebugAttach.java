package city.subroutine.sandbox.host.debug;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.ListeningConnector;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Хост слушает JDWP-соединение, воркер подключается к нему сам ({@code server=n}): так не нужно угадывать
 * свободный порт и нет гонки при подключении. Слушатель — только на 127.0.0.1.
 */
public final class DebugAttach implements AutoCloseable {

    private static final String CONNECTOR = "com.sun.jdi.SocketListen";

    private final ListeningConnector connector;
    private final Map<String, Connector.Argument> arguments;
    private final String address;
    private boolean listening;

    private DebugAttach(ListeningConnector connector, Map<String, Connector.Argument> arguments, String address) {
        this.connector = connector;
        this.arguments = arguments;
        this.address = address;
        this.listening = true;
    }

    public static DebugAttach listen(Duration acceptTimeout) throws IOException {
        ListeningConnector connector = Bootstrap.virtualMachineManager().listeningConnectors().stream()
                .filter(c -> c.name().equals(CONNECTOR))
                .findFirst()
                .orElseThrow(() -> new IOException("JDI-коннектор " + CONNECTOR + " недоступен"));
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("port").setValue("0");
        Connector.Argument local = arguments.get("localAddress");
        if (local != null) {
            local.setValue("127.0.0.1");
        }
        arguments.get("timeout").setValue(Long.toString(acceptTimeout.toMillis()));
        try {
            String address = connector.startListening(arguments);
            return new DebugAttach(connector, arguments, address);
        } catch (IllegalConnectorArgumentsException e) {
            throw new IOException("Некорректные аргументы JDI: " + e.getMessage(), e);
        }
    }

    /** Флаг JVM воркера: подключиться к хосту и ждать resume. */
    public String jvmAgentOption() {
        String port = address.substring(address.lastIndexOf(':') + 1);
        return "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,quiet=y,address=127.0.0.1:" + port;
    }

    public VirtualMachine accept() throws IOException {
        try {
            return connector.accept(arguments);
        } catch (IllegalConnectorArgumentsException e) {
            throw new IOException("Некорректные аргументы JDI: " + e.getMessage(), e);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (listening) {
            listening = false;
            try {
                connector.stopListening(arguments);
            } catch (IOException | IllegalConnectorArgumentsException ignored) {
                // уже остановлен
            }
        }
    }
}
