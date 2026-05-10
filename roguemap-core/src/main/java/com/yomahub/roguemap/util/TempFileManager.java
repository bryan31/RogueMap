package com.yomahub.roguemap.util;

import com.yomahub.roguemap.memory.UnsafeOps;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 临时文件管理器
 *
 * 负责临时 MMAP 文件的创建、清理和生命周期管理
 */
public class TempFileManager {

    private static final String TEMP_FILE_PREFIX = "roguemap-temp-";
    private static final String TEMP_FILE_SUFFIX = ".mmap";
    private static final long CLEANUP_AGE_HOURS = 24; // 清理超过24小时的临时文件
    private static final String UNMAP_GC_ONLY = "gc-only";
    private static final String UNMAP_UNSAFE_INVOKE_CLEANER = "unsafe-invoke-cleaner";
    private static final String UNMAP_DIRECT_CLEANER = "direct-cleaner";

    static {
        // 启动时自动清理过期的临时文件
        cleanupOldTempFiles();
    }

    /**
     * 创建临时文件
     *
     * @return 临时文件对象
     */
    public static File createTempFile() {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            String fileName = TEMP_FILE_PREFIX +
                              System.currentTimeMillis() + "-" +
                              UUID.randomUUID().toString().substring(0, 8) +
                              TEMP_FILE_SUFFIX;

            File tempFile = new File(tempDir, fileName);

            // 标记为 JVM 退出时删除
            tempFile.deleteOnExit();

            return tempFile;
        } catch (Exception e) {
            throw new RuntimeException("创建临时文件失败", e);
        }
    }

    /**
     * 注册 Shutdown Hook 确保文件被删除
     *
     * @param file 要删除的文件
     * @param buffers 需要 unmap 的缓冲区
     */
    public static void registerCleanupHook(File file, MappedByteBuffer... buffers) {
        Thread cleanupHook = new Thread(() -> {
            try {
                // 强制 unmap 所有缓冲区
                for (MappedByteBuffer buffer : buffers) {
                    if (buffer != null) {
                        forceUnmap(buffer);
                    }
                }

                // 建议 GC 运行（不保证立即执行）
                System.gc();
                System.runFinalization();

                // 尝试删除文件
                if (file.exists()) {
                    boolean deleted = file.delete();
                    if (!deleted) {
                        // 如果第一次删除失败，等待一小段时间后重试
                        Thread.sleep(100);
                        file.delete();
                    }
                }
            } catch (Exception e) {
                // Shutdown 过程中不抛出异常
                System.err.println("清理临时文件失败: " + file.getAbsolutePath() + ", " + e.getMessage());
            }
        }, "RogueMap-TempFile-Cleanup");

        // 设置为守护线程
        cleanupHook.setDaemon(true);
        Runtime.getRuntime().addShutdownHook(cleanupHook);
    }

    /**
     * 强制 unmap MappedByteBuffer
     * 解决 Windows 平台文件句柄未释放的问题
     *
     * unsafe 内存访问模式下，优先使用 Java 9+ 的 Unsafe.invokeCleaner()，
     * 在 Java 8 上降级使用 DirectByteBuffer.cleaner().clean()。
     * registered 内存访问模式下不再触发 Unsafe 清理，避免 JDK 25+ 运行时告警；
     * 调用方会先释放注册引用，再由 JVM/OS 处理映射生命周期。
     *
     * @param buffer 要 unmap 的缓冲区
     */
    public static void forceUnmap(MappedByteBuffer buffer) {
        if (buffer == null) {
            return;
        }

        String strategy = selectUnmapStrategy(UnsafeOps.memoryAccessMode(), currentFeatureVersion());
        if (UNMAP_GC_ONLY.equals(strategy)) {
            return;
        }

        if (UNMAP_UNSAFE_INVOKE_CLEANER.equals(strategy)) {
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                Object unsafe = unsafeField.get(null);
                Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", java.nio.ByteBuffer.class);
                invokeCleaner.invoke(unsafe, buffer);
                return; // Java 9+ 路径成功
            } catch (NoSuchMethodException e) {
                // Java 8：invokeCleaner 不存在，降级到旧方式
            } catch (Exception e) {
                System.err.println("警告: invokeCleaner 调用失败: " + e.getMessage());
            }
        }

        // Java 8 降级方式：DirectByteBuffer.cleaner().clean()
        try {
            Method cleanerMethod = buffer.getClass().getMethod("cleaner");
            cleanerMethod.setAccessible(true);
            Object cleaner = cleanerMethod.invoke(buffer);

            if (cleaner != null) {
                Method cleanMethod = cleaner.getClass().getMethod("clean");
                cleanMethod.invoke(cleaner);
            }
        } catch (Exception e) {
            System.err.println("警告: 无法强制 unmap MappedByteBuffer: " + e.getMessage());
        }
    }

    static String selectUnmapStrategy(String memoryAccessMode, int featureVersion) {
        if ("registered".equals(memoryAccessMode)) {
            return UNMAP_GC_ONLY;
        }
        return featureVersion >= 9 ? UNMAP_UNSAFE_INVOKE_CLEANER : UNMAP_DIRECT_CLEANER;
    }

    private static int currentFeatureVersion() {
        String specVersion = System.getProperty("java.specification.version", "8");
        if (specVersion.startsWith("1.")) {
            specVersion = specVersion.substring(2);
        }
        int dot = specVersion.indexOf('.');
        if (dot >= 0) {
            specVersion = specVersion.substring(0, dot);
        }
        try {
            return Integer.parseInt(specVersion);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    /**
     * 清理过期的临时文件
     * 在启动时调用，清理之前未正常清理的临时文件
     */
    public static void cleanupOldTempFiles() {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            Path tempPath = Paths.get(tempDir);

            if (!Files.exists(tempPath)) {
                return;
            }

            long now = System.currentTimeMillis();
            long cutoffTime = now - TimeUnit.HOURS.toMillis(CLEANUP_AGE_HOURS);

            try (Stream<Path> files = Files.list(tempPath)) {
                files.filter(path -> {
                    String fileName = path.getFileName().toString();
                    return fileName.startsWith(TEMP_FILE_PREFIX) &&
                           fileName.endsWith(TEMP_FILE_SUFFIX);
                })
                .filter(path -> {
                    try {
                        long lastModified = Files.getLastModifiedTime(path).toMillis();
                        return lastModified < cutoffTime;
                    } catch (IOException e) {
                        return false;
                    }
                })
                .forEach(path -> {
                    try {
                        Files.delete(path);
                        System.out.println("清理过期临时文件: " + path.getFileName());
                    } catch (IOException e) {
                        // 忽略删除失败的情况
                    }
                });
            }
        } catch (Exception e) {
            // 清理失败不影响程序运行
            System.err.println("清理过期临时文件时发生错误: " + e.getMessage());
        }
    }

    /**
     * 立即删除文件（尽力而为）
     *
     * @param file 要删除的文件
     * @param buffers 需要先 unmap 的缓冲区
     */
    public static void deleteImmediately(File file, MappedByteBuffer... buffers) {
        if (file == null || !file.exists()) {
            return;
        }

        try {
            // 先 unmap 所有缓冲区
            for (MappedByteBuffer buffer : buffers) {
                if (buffer != null) {
                    forceUnmap(buffer);
                }
            }

            // 建议 GC
            System.gc();
            System.runFinalization();

            // 尝试删除
            if (!file.delete()) {
                // 如果删除失败，等待一小段时间后重试
                Thread.sleep(100);
                if (!file.delete()) {
                    // 仍然失败，标记为退出时删除
                    file.deleteOnExit();
                }
            }
        } catch (Exception e) {
            // 尽力而为，失败时标记为退出时删除
            file.deleteOnExit();
        }
    }
}
