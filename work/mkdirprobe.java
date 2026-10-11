import java.nio.file.*;
public class mkdirprobe { public static void main(String[] a) throws Exception { Files.createDirectories(Paths.get(System.getProperty("user.dir"),"probe-out","x")); System.out.println("created-ok"); } }
