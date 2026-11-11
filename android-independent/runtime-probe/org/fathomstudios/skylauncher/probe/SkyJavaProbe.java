package org.fathomstudios.skylauncher.probe;

/**
 * Runs only inside the independent Android Java 21 HotSpot VM.
 * It validates actual JVM execution, not Android ART compatibility.
 */
public final class SkyJavaProbe {
    public static void main(String[] args) {
        String version = System.getProperty("java.version", "unknown");
        String vendor = System.getProperty("java.vendor", "unknown");
        String arch = System.getProperty("os.arch", "unknown");
        System.out.println("SKYLAUNCHER_OPENJDK_PROBE: " + version
                + " | " + vendor + " | " + arch);
        if (!version.startsWith("21.") && !version.equals("21"))
            throw new IllegalStateException("OpenJDK 21 expected, got " + version);
    }
}
