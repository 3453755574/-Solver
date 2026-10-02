package com.yjbrly.fivesolver;

import android.content.Context;
import android.content.res.AssetManager;

import com.yjbrly.fivesolver.MainActivity.Point;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RapfiEngine {
    private static final int IO_BUFFER_SIZE = 1024 * 1024;
    private static final long EXTRA_WAIT_MS = 12000L;
    private static final Pattern MOVE_PATTERN = Pattern.compile("^\\d{1,2},\\d{1,2}$");

    private static EngineLogger logger;
    private static File engineBaseDir;
    private static RapfiEngine instance;

    public interface EngineLogger {
        void onSend(String line);
        void onReceive(String line);
    }

    public static void setLogger(EngineLogger l) { logger = l; }

    public static void init(Context context, String assetSubPath) {
        File filesDir = context.getFilesDir();
        engineBaseDir = new File(filesDir, "engine");
        if (!engineBaseDir.exists()) engineBaseDir.mkdirs();
        copyEngineAssets(context, assetSubPath);
    }

    private static void copyEngineAssets(Context ctx, String assetSubPath) {
        AssetManager am = ctx.getAssets();
        String[] filesToCopy = {
			"pbrain-rapfi", "config.toml",
			"mix9svqfreestyle_bsmix.bin.lz4",
			"mix9svqrenju_bs15_black.bin.lz4",
			"mix9svqrenju_bs15_white.bin.lz4",
			"mix9svqstandard_bs15.bin.lz4",
			"model210901.bin"
        };
        byte[] buf = new byte[65536];
        for (String fileName : filesToCopy) {
            File dest = new File(engineBaseDir, fileName);
            if (dest.exists()) continue;
            InputStream in = null;
            OutputStream out = null;
            try {
                if (assetSubPath != null && !assetSubPath.isEmpty()) {
                    in = am.open(assetSubPath + "/" + fileName);
                } else {
                    in = am.open(fileName);
                }
                out = new FileOutputStream(dest);
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                if (logger != null) logger.onReceive("[引擎] 复制文件: " + fileName);
            } catch (IOException e) {
                if (logger != null)
                    logger.onReceive("[引擎] 跳过文件: " + fileName + " (" + e.getMessage() + ")");
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
        }
        File exe = new File(engineBaseDir, "pbrain-rapfi");
        if (exe.exists()) exe.setExecutable(true);
    }

    public static synchronized RapfiEngine getInstance() {
        if (instance == null) instance = new RapfiEngine();
        return instance;
    }

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private final Object ioLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean stdinClosed = true, stdoutClosed = true;
    private volatile int currentTimeLimit = 5000;
    private volatile int currentThreads = 8;
    private volatile int currentRule = 0;
    private volatile int currentCautionFactor = 0;   // 默认 0（不谨慎，更强）
    private volatile String currentSearchType = "alphabeta";
    private volatile int boardSize = 15;

    private RapfiEngine() {}

    public void setBoardSize(int size) { boardSize = size; }
    public void setThreads(int threads) { currentThreads = threads; }
    public void setTimeLimitMs(int ms) { this.currentTimeLimit = ms; }

    public void setRule(int rule) {
        currentRule = rule;
        synchronized (ioLock) {
            try { sendLineLocked("info rule " + rule); } catch (IOException ignored) {}
        }
    }

    public void setCautionFactor(int factor) {
        currentCautionFactor = factor;
        synchronized (ioLock) {
            try { sendLineLocked("info caution_factor " + factor); } catch (IOException ignored) {}
        }
    }

    public void setSearchType(String type) {
        currentSearchType = type;
        synchronized (ioLock) {
            try { sendLineLocked("INFO search_type " + type); } catch (IOException ignored) {}
        }
    }

    public void updateTimeout() {
        synchronized (ioLock) {
            try { sendLineLocked("info timeout_turn " + currentTimeLimit); } catch (IOException ignored) {}
        }
    }

    public boolean startIfNeeded() {
        if (started.get() && process != null && isProcessAlive(process)) return true;
        synchronized (ioLock) {
            if (started.get() && process != null && isProcessAlive(process)) return true;
            if (process != null) { process.destroy(); waitForProcess(process, 300); process = null; }
            closeQuietly(stdout); closeQuietly(stdin);
            stdinClosed = true; stdoutClosed = true;
            started.set(false);
            try {
                File exe = new File(engineBaseDir, "pbrain-rapfi");
                if (!exe.exists() || !exe.canExecute()) {
                    if (logger != null) logger.onReceive("[引擎] 可执行文件不存在或不可执行: " + exe.getAbsolutePath());
                    return false;
                }
                ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath());
                pb.directory(engineBaseDir);
                pb.redirectErrorStream(true);
                process = pb.start();
                stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"), IO_BUFFER_SIZE);
                stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"), IO_BUFFER_SIZE);
                stdinClosed = false; stdoutClosed = false;

                sendLineLocked("START " + boardSize);
                sendLineLocked("info thread_num " + currentThreads);
                sendLineLocked("info timeout_turn " + currentTimeLimit);
                sendLineLocked("info rule " + currentRule);
                sendLineLocked("info caution_factor " + currentCautionFactor);
                sendLineLocked("INFO search_type " + currentSearchType);
                sendLineLocked("yxboard");
                sendLineLocked("done");
                sendLineLocked("yxshowforbid");
                sendLineLocked("INFO show_detail 3");

                drainRemainingLines();
                started.set(true);
                return true;
            } catch (Exception e) {
                if (logger != null) logger.onReceive("[引擎] 启动失败: " + e);
                return false;
            }
        }
    }

    private void sendLineLocked(String line) throws IOException {
        if (stdin == null || stdinClosed) return;
        stdin.write(line); stdin.write("\n\r"); stdin.flush();
        if (logger != null) logger.onSend(line);
    }

    public int[] getBestMove(List<Point> history, boolean aiIsBlack) {
        if (!startIfNeeded()) return null;
        synchronized (ioLock) {
            try {
                drainRemainingLines();
                StringBuilder boardStr = new StringBuilder("board\r\n");
                for (int i = 0; i < history.size(); i++) {
                    Point p = history.get(i);
                    int type = (i % 2 == 0) == aiIsBlack ? 1 : 2;
                    boardStr.append(p.x).append(',').append(p.y).append(',').append(type).append("\n\r");
                }
                boardStr.append("DONE\n\r");
                sendLineLocked(boardStr.toString().trim());

                long deadline = System.currentTimeMillis() + currentTimeLimit + EXTRA_WAIT_MS;
                int[] move = readMove(deadline);
                drainRemainingLines();
                return move;
            } catch (IOException e) {
                return null;
            }
        }
    }

    public void getMultiBestMoves(List<Point> history, boolean aiIsBlack, int n, int timeLimitMs, final MainActivity callback) {
        if (!startIfNeeded()) {
            callback.onMultiAnalysisComplete(0);
            return;
        }
        synchronized (ioLock) {
            try {
                drainRemainingLines();
                sendLineLocked("YXNBEST " + n);
                StringBuilder boardStr = new StringBuilder("board\r\n");
                for (int i = 0; i < history.size(); i++) {
                    Point p = history.get(i);
                    int type = (i % 2 == 0) == aiIsBlack ? 1 : 2;
                    boardStr.append(p.x).append(',').append(p.y).append(',').append(type).append("\n\r");
                }
                boardStr.append("DONE\n\r");
                sendLineLocked(boardStr.toString().trim());

                long deadline = System.currentTimeMillis() + timeLimitMs + EXTRA_WAIT_MS;
                int candidateCount = 0;
                int currentPv = -1, currentEval = 0;
                double currentWinrate = 0;
                String currentDepth = "";

                while (System.currentTimeMillis() < deadline) {
                    if (stdoutClosed || stdout == null) break;
                    if (stdout.ready()) {
                        String line = stdout.readLine();
                        if (line == null) { closeQuietly(stdout); stdoutClosed = true; break; }
                        line = line.trim();
                        if (logger != null) logger.onReceive(line);

                        if (line.startsWith("INFO PV ")) {
                            try { currentPv = Integer.parseInt(line.substring(8).trim()); } catch (Exception ignored) {}
                        } else if (line.startsWith("INFO EVAL ")) {
                            try { currentEval = Integer.parseInt(line.substring(10).trim()); } catch (Exception ignored) {}
                        } else if (line.startsWith("INFO WINRATE ")) {
                            try { currentWinrate = Double.parseDouble(line.substring(13).trim()); } catch (Exception ignored) {}
                        } else if (line.startsWith("INFO DEPTH ")) {
                            try { currentDepth = String.valueOf(Integer.parseInt(line.substring(11).trim())); } catch (Exception ignored) {}
                        } else if (line.startsWith("INFO SELDEPTH ")) {
                            try {
                                int seldepth = Integer.parseInt(line.substring(14).trim());
                                if (!currentDepth.isEmpty()) currentDepth += "-" + seldepth;
                            } catch (Exception ignored) {}
                        } else if (line.startsWith("INFO BESTLINE ")) {
                            try {
                                String coords = line.substring(14).trim();
                                String[] coordPairs = coords.split(" ");
                                if (coordPairs.length > 0) {
                                    String[] xy = coordPairs[0].split(",");
                                    int x = Integer.parseInt(xy[0]);
                                    int y = Integer.parseInt(xy[1]);
                                    if (currentPv >= 0) {
                                        callback.onMultiAnalysisCandidate(currentPv + 1, x, y, currentEval, currentWinrate, currentDepth);
                                        candidateCount++;
                                    }
                                }
                            } catch (Exception ignored) {}
                        } else {
                            Matcher matcher = MOVE_PATTERN.matcher(line);
                            if (matcher.find()) {
                                String[] parts = line.split(",");
                                try {
                                    int x = Integer.parseInt(parts[0]);
                                    int y = Integer.parseInt(parts[1]);
                                    if (x >= 0 && x < boardSize && y >= 0 && y < boardSize) break;
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    } else {
                        LockSupport.parkNanos(100000L);
                        if (process != null && !isProcessAlive(process)) break;
                    }
                }
                drainRemainingLines();
                callback.onMultiAnalysisComplete(candidateCount);
            } catch (IOException e) {
                callback.onMultiAnalysisComplete(0);
            }
        }
    }

    private int[] readMove(long deadline) throws IOException {
        while (System.currentTimeMillis() < deadline) {
            if (stdoutClosed || stdout == null) return null;
            if (stdout.ready()) {
                String line = stdout.readLine();
                if (line == null) { closeQuietly(stdout); stdoutClosed = true; return null; }
                line = line.trim();
                if (logger != null) logger.onReceive(line);
                Matcher matcher = MOVE_PATTERN.matcher(line);
                if (matcher.find()) {
                    String[] parts = line.split(",");
                    try {
                        int x = Integer.parseInt(parts[0]);
                        int y = Integer.parseInt(parts[1]);
                        if (x >= 0 && x < boardSize && y >= 0 && y < boardSize) {
                            return new int[]{x, y};
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                LockSupport.parkNanos(100000L);
                if (process != null && !isProcessAlive(process)) return null;
            }
        }
        return null;
    }

    private void drainRemainingLines() throws IOException {
        int count = 0;
        while (count < 50 && stdout != null && !stdoutClosed && stdout.ready()) {
            String line = stdout.readLine();
            if (line == null) break;
            if (logger != null) logger.onReceive(line.trim());
            count++;
        }
    }

    public void restart() {
        if (!started.get()) return;
        synchronized (ioLock) {
            try { sendLineLocked("RESTART"); } catch (IOException ignored) {}
        }
    }

    public void drainEngineOutput() {
        synchronized (ioLock) {
            try { drainRemainingLines(); } catch (IOException ignored) {}
        }
    }

    public void stop() {
        synchronized (ioLock) {
            if (stdin != null && !stdinClosed) {
                try { stdin.write("END\n\r"); stdin.flush(); } catch (IOException ignored) {}
            }
            closeQuietly(stdout); closeQuietly(stdin);
            if (process != null) { process.destroy(); waitForProcess(process, 1000); process = null; }
            started.set(false);
        }
    }

    private static boolean isProcessAlive(Process p) {
        if (p == null) return false;
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    private static void waitForProcess(Process p, long timeoutMs) {
        try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }
}
