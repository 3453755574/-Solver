package com.yjbrly.fivesolver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private BoardView boardView;
    private TextView tvLog;
    private ScrollView scrollLog;
    private TextView tvDepth;
    private TextView tvEval;
    private TextView tvStep;
    private Button btnMultiAnalysis;
    private Button btnNewGame;
    private Button btnUndo;
    private EditText etSgfInput;
    private Button btnLoadSgf;
    private Button btnPrevMove;
    private Button btnNextMove;

    private List<Point> moveHistory = new ArrayList<Point>();
    private final boolean[] aiControlBlack = new boolean[]{false};
    private final boolean[] aiControlWhite = new boolean[]{true};
    private boolean gameOver = false;
    private boolean aiThinking = false;
    private volatile boolean aiInterrupted = false;
    private AtomicBoolean aiMoveRequested = new AtomicBoolean(false);
    private Handler handler = new Handler(Looper.getMainLooper());

    private final int currentThreads = 8;
    private int thinkTimeMs = 5000;
    private int cautionFactor = 3;
    private String searchType = "alphabeta";
    private final int currentRule = 0;
    private final int boardSize = 15;
    private final boolean showMoveNumbers = true;

    private static final int MAX_LOG_LINES = 500;

    private SoundPool soundPool;
    private int soundId;
    private boolean soundLoaded = false;

    private boolean engineReady = false;
    private boolean engineBusy = true;
    private boolean newGameDialogVisible = false;


    private boolean multiAnalysisActive = false;
    private int currentPvIndex = -1;
    private int currentCandidateEval = 0;
    private double currentCandidateWinrate = 0;
    private String currentCandidateDepth = "";


    private boolean isReviewMode = false;
    private List<Point> recordedMoves = new ArrayList<Point>();
    private int reviewIndex = 0;
    private AtomicBoolean analyzingReview = new AtomicBoolean(false);

    private static final Pattern MOVE_PATTERN = Pattern.compile("^\\d{1,2},\\d{1,2}$");
    private static final Pattern SGF_PATTERN = Pattern.compile("([a-o])(\\d{1,2})", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initEngineAndStart();
    }

    private void initEngineAndStart() {
        boardView = (BoardView) findViewById(R.id.boardView);
        boardView.setBoardSize(boardSize);
        boardView.setShowMoveNumbers(showMoveNumbers);
        boardView.setEnabled(false);

        tvLog = (TextView) findViewById(R.id.tvLog);
        scrollLog = (ScrollView) findViewById(R.id.scrollLog);
        tvDepth = (TextView) findViewById(R.id.tvDepth);
        tvEval = (TextView) findViewById(R.id.tvEval);
        tvStep = (TextView) findViewById(R.id.tvStep);
        btnMultiAnalysis = (Button) findViewById(R.id.btnMultiAnalysis);
        btnNewGame = (Button) findViewById(R.id.btnNewGame);
        btnUndo = (Button) findViewById(R.id.btnUndo);
        etSgfInput = (EditText) findViewById(R.id.etSgfInput);
        btnLoadSgf = (Button) findViewById(R.id.btnLoadSgf);
        btnPrevMove = (Button) findViewById(R.id.btnPrevMove);
        btnNextMove = (Button) findViewById(R.id.btnNextMove);

        initSound();

        boardView.setOnCandidateClickListener(new BoardView.OnCandidateClickListener() {
                @Override
                public void onCandidateClick(int rank, int x, int y) {
                    if (multiAnalysisActive && boardView.isMultiAnalysisComplete() && !aiThinking && !gameOver && engineReady && !engineBusy) {
                        appendLog("选择候选点 #" + rank + " (" + coordToString(x, y) + ")");
                        executeCandidateMove(x, y);
                    }
                }
            });

        RapfiEngine.setLogger(new RapfiEngine.EngineLogger() {
                @Override
                public void onSend(final String line) {
                    handler.post(new Runnable() {
                            @Override
                            public void run() {
                                appendLog(">> " + line);
                            }
                        });
                }

                @Override
                public void onReceive(final String line) {
                    handler.post(new Runnable() {
                            @Override
                            public void run() {
                                appendLog("<< " + line);
                                parseEngineMessage(line);
                            }
                        });
                }
            });

        btnMultiAnalysis.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!engineReady || engineBusy || aiThinking || gameOver) {
                        appendLog("引擎繁忙或未就绪，无法多点分析");
                        return;
                    }
                    if (moveHistory.isEmpty()) {
                        appendLog("棋盘上无棋子，请先落子或开始新局");
                        return;
                    }
                    startMultiAnalysis();
                }
            });

        btnNewGame.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (newGameDialogVisible) return;
                    if (!engineReady || engineBusy) {
                        appendLog("引擎未就绪，无法新局");
                        return;
                    }
                    if (aiThinking) {
                        aiInterrupted = true;
                        boardView.setEnabled(false);
                        boardView.clearSelection();
                    }
                    stopMultiAnalysis();
                    boardView.clearLostPoints();
                    showNewGameDialog();
                }
            });

        btnUndo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isReviewMode) {
                        appendLog("打谱模式下不能悔棋，请使用导航按钮");
                        return;
                    }
                    if (!engineReady || engineBusy || aiThinking) {
                        appendLog("引擎繁忙，无法悔棋");
                        return;
                    }
                    if (moveHistory.size() < 2) {
                        appendLog("至少需要两步才能悔棋");
                        return;
                    }
                    stopMultiAnalysis();
                    boardView.clearLostPoints();
                    moveHistory.remove(moveHistory.size() - 1);
                    moveHistory.remove(moveHistory.size() - 1);
                    gameOver = false;
                    boardView.setWinLine(null);
                    boardView.clearSelection();
                    boardView.clearHint();
                    updateBoard();
                    appendLog("悔棋两步");
                    if (currentTurnIsAI()) {
                        if (aiMoveRequested.compareAndSet(false, true)) {
                            aiThinking = true;
                            boardView.setEnabled(false);
                            final List<Point> snapshot = new ArrayList<Point>(moveHistory);
                            new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        triggerAIMove(snapshot);
                                    }
                                }).start();
                        }
                    } else {
                        boardView.setEnabled(true);
                    }
                }
            });

        btnLoadSgf.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isReviewMode) {
                        appendLog("请先通过「新对局」选择「打谱模式」");
                        return;
                    }
                    loadSgfFromInput();
                }
            });


        btnPrevMove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isReviewMode) return;
                    if (analyzingReview.get()) {
                        appendLog("正在分析中，请稍候...");
                        return;
                    }
                    setAllInteractionsEnabled(false);
                    if (reviewIndex > 0) {
                        reviewIndex--;
                        applyReviewStep();
                    } else {
                        appendLog("已到开局");
                        setAllInteractionsEnabled(true);
                    }
                }
            });

        btnNextMove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isReviewMode) return;
                    if (analyzingReview.get()) {
                        appendLog("正在分析中，请稍候...");
                        return;
                    }
                    setAllInteractionsEnabled(false);
                    if (reviewIndex < recordedMoves.size()) {
                        reviewIndex++;
                        applyReviewStep();
                    } else {
                        appendLog("已到终局");
                        setAllInteractionsEnabled(true);
                    }
                }
            });

        boardView.setOnMoveListener(new BoardView.OnMoveListener() {
                @Override
                public void onMove(int x, int y) {
                    if (isReviewMode) {
                        appendLog("打谱模式下不能落子，请使用导航");
                        return;
                    }
                    if (analyzingReview.get()) {
                        appendLog("正在分析中，禁止落子");
                        return;
                    }
                    if (!boardView.isEnabled() || gameOver || aiThinking || !engineReady || engineBusy) {
                        if (engineBusy || !boardView.isEnabled()) appendLog("引擎未就绪，请稍候...");
                        return;
                    }
                    if (GameLogic.isGameFinished(moveHistory)) {
                        gameOver = true;
                        appendLog("游戏已结束，无法继续落子");
                        boardView.setEnabled(false);
                        return;
                    }
                    if (isHumanTurn()) {
                        if (GameLogic.isOccupied(moveHistory, x, y)) {
                            appendLog("此处已有棋子");
                            return;
                        }
                        boardView.clearLostPoints();
                        stopMultiAnalysis();
                        moveHistory.add(new Point(x, y));
                        updateBoard();
                        playMoveSound();
                        boardView.clearSelection();
                        checkGameEnd();
                        if (gameOver) return;
                        if (!isHumanTurn()) {
                            if (aiMoveRequested.compareAndSet(false, true)) {
                                aiThinking = true;
                                aiInterrupted = false;
                                boardView.setEnabled(false);
                                final List<Point> snapshot = new ArrayList<Point>(moveHistory);
                                new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            triggerAIMove(snapshot);
                                        }
                                    }).start();
                            }
                        }
                    }
                }
            });

        GameLogic.setBoardSize(boardSize);

        appendLog("引擎启动中，请稍候...");
        engineBusy = true;
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                try {
                    RapfiEngine.init(MainActivity.this, "");
                    return true;
                } catch (Exception e) {
                    appendLog("引擎初始化异常: " + e.toString());
                    e.printStackTrace();
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (success) {
                    engineReady = true;
                    new Thread(new Runnable() {
                            @Override
                            public void run() {
                                RapfiEngine engine = RapfiEngine.getInstance();
                                engine.setBoardSize(boardSize);
                                engine.setThreads(currentThreads);
                                engine.setTimeLimitMs(thinkTimeMs);
                                engine.setRule(currentRule);
                                engine.setCautionFactor(cautionFactor);
                                engine.setSearchType(searchType);
                                final boolean started = engine.startIfNeeded();
                                handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (started) {
                                                appendLog("引擎就绪，请点击「新对局」开始");
                                                engineBusy = false;
                                                boardView.setEnabled(true);
                                                updateUIMode();
                                                setAllInteractionsEnabled(true);
                                            } else {
                                                appendLog("引擎启动失败，请检查 assets 中的引擎文件");
                                                engineBusy = false;
                                            }
                                        }
                                    });
                            }
                        }).start();
                } else {
                    engineBusy = false;
                }
            }
        }.execute();
    }


    private void syncAllConfigs() {
        RapfiEngine engine = RapfiEngine.getInstance();
        boolean started = engine.startIfNeeded();
        if (!started) {
            appendLog("警告：引擎启动失败，配置可能未应用");
            return;
        }
        engine.setThreads(currentThreads);
        engine.setTimeLimitMs(thinkTimeMs);
        engine.updateTimeout();
        engine.setCautionFactor(cautionFactor);
        engine.setSearchType(searchType);
        engine.setRule(currentRule);
        appendLog("配置已强制同步：思考时间=" + (thinkTimeMs/1000) + "秒，谨慎因子=" + cautionFactor +
                  "，搜索类型=" + searchType + "，线程=" + currentThreads);
    }

    private void setAllInteractionsEnabled(boolean enabled) {
        if (isReviewMode) {
            btnPrevMove.setEnabled(enabled && reviewIndex > 0);
            btnNextMove.setEnabled(enabled && reviewIndex < recordedMoves.size());
        } else {
            btnPrevMove.setEnabled(false);
            btnNextMove.setEnabled(false);
        }
        if (!gameOver && !aiThinking && !isReviewMode) {
            boardView.setEnabled(enabled);
        } else if (isReviewMode) {
            boardView.setEnabled(false);
        }
    }



    private void enterReviewMode() {
        if (isReviewMode) exitReviewMode();
        isReviewMode = true;
        boardView.setEnabled(false);
        boardView.clearLostPoints();
        stopMultiAnalysis();
        moveHistory.clear();
        recordedMoves.clear();
        reviewIndex = 0;
        gameOver = false;
        aiThinking = false;
        aiInterrupted = false;
        aiMoveRequested.set(false);
        boardView.setWinLine(null);
        boardView.clearSelection();
        boardView.clearHint();
        boardView.clearCandidates();
        tvDepth.setText("搜索层数：--");
        tvEval.setText("胜率：--");
        tvStep.setText("0/0");

        appendLog("打谱模式已开启，请粘贴棋谱并点击「加载」");
        updateUIMode();
        setAllInteractionsEnabled(true);
    }

    private void loadSgfFromInput() {
        String input = etSgfInput.getText().toString().trim();
        if (input.isEmpty()) {
            appendLog("请先粘贴棋谱字符串");
            return;
        }
        List<Point> moves = parseSgfMoves(input);
        if (moves.isEmpty()) {
            appendLog("棋谱解析失败，请检查格式（如 h8g9g10...）");
            return;
        }
        recordedMoves.clear();
        recordedMoves.addAll(moves);
        reviewIndex = 0;
        moveHistory.clear();
        boardView.setWinLine(null);
        boardView.clearSelection();
        boardView.clearHint();
        boardView.clearCandidates();
        boardView.clearLostPoints();

        syncAllConfigs();

        appendLog("棋谱加载成功，共 " + recordedMoves.size() + " 手");
        applyReviewStep();
        appendLog("使用 < 和 > 按钮步进，AI 将自动分析当前局面（每次等待 " + (thinkTimeMs / 1000) + " 秒）");
    }

    private void exitReviewMode() {
        isReviewMode = false;
        recordedMoves.clear();
        reviewIndex = 0;
        moveHistory.clear();
        gameOver = false;
        boardView.setWinLine(null);
        boardView.clearSelection();
        boardView.clearHint();
        boardView.clearCandidates();
        boardView.clearLostPoints();
        boardView.setEnabled(true);
        tvDepth.setText("搜索层数：--");
        tvEval.setText("胜率：--");
        tvStep.setText("0/0");
        appendLog("退出打谱模式");
        updateUIMode();
        setAllInteractionsEnabled(true);
        if (engineReady && !engineBusy) {
            boardView.setEnabled(true);
        }
    }

    private void applyReviewStep() {
        if (!isReviewMode) return;
        List<Point> subList = new ArrayList<Point>();
        for (int i = 0; i < reviewIndex && i < recordedMoves.size(); i++) {
            subList.add(recordedMoves.get(i));
        }
        moveHistory = subList;
        updateBoard();
        if (reviewIndex > 0) {
            playMoveSound();
        }
        tvStep.setText(reviewIndex + "/" + recordedMoves.size());
        tvDepth.setText("搜索层数：--");
        tvEval.setText("胜率：--");
        boardView.clearHint();

        if (GameLogic.isFiveInRow(moveHistory)) {
            int lastColor = (moveHistory.size() - 1) % 2 == 0 ? GameLogic.BLACK : GameLogic.WHITE;
            String winner = (lastColor == GameLogic.BLACK) ? "黑方" : "白方";
            appendLog("打谱：已出现连五，" + winner + "获胜");
            boardView.setWinLine(GameLogic.getWinningLine(moveHistory));
            setAllInteractionsEnabled(true);
            return;
        }
        triggerReviewAnalysis(moveHistory);
    }

    private void updateUIMode() {
        View layoutInput = findViewById(R.id.layoutSgfInput);
        View layoutControls = findViewById(R.id.layoutSgfControls);
        if (isReviewMode) {
            btnMultiAnalysis.setVisibility(View.GONE);
            btnUndo.setVisibility(View.GONE);
            layoutInput.setVisibility(View.VISIBLE);
            layoutControls.setVisibility(View.VISIBLE);
        } else {
            btnMultiAnalysis.setVisibility(View.VISIBLE);
            btnUndo.setVisibility(View.VISIBLE);
            layoutInput.setVisibility(View.GONE);
            layoutControls.setVisibility(View.GONE);
        }
        setAllInteractionsEnabled(isReviewMode);
    }

    private void triggerReviewAnalysis(final List<Point> history) {
        if (!analyzingReview.compareAndSet(false, true)) {
            return;
        }

        if (!engineReady || engineBusy) {
            analyzingReview.set(false);
            setAllInteractionsEnabled(true);
            return;
        }
        if (history.isEmpty()) {
            tvDepth.setText("搜索层数：--");
            tvEval.setText("胜率：--");
            boardView.clearHint();
            analyzingReview.set(false);
            setAllInteractionsEnabled(true);
            return;
        }

        setAllInteractionsEnabled(false);


        syncAllConfigs();

        new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean isBlackTurn = history.size() % 2 == 0;
                    RapfiEngine engine = RapfiEngine.getInstance();
                    long startTime = System.currentTimeMillis();
                    final int[] bestMove = engine.getBestMove(history, isBlackTurn);
                    final long elapsed = System.currentTimeMillis() - startTime;

                    handler.post(new Runnable() {
                            @Override
                            public void run() {
                                analyzingReview.set(false);
                                setAllInteractionsEnabled(true);
                                if (bestMove == null) {
                                    appendLog("分析未返回有效着法（超时 " + (thinkTimeMs/1000) + " 秒，实际耗时 " + (elapsed/1000) + " 秒）");
                                } else {
                                    appendLog("分析完成，最佳着法：" + bestMove[0] + "," + bestMove[1] + "，耗时 " + (elapsed/1000) + " 秒");
                                }
                            }
                        });
                }
            }).start();
    }

    private List<Point> parseSgfMoves(String sgf) {
        List<Point> moves = new ArrayList<Point>();
        Matcher matcher = SGF_PATTERN.matcher(sgf.trim());
        while (matcher.find()) {
            String colStr = matcher.group(1).toLowerCase();
            String rowStr = matcher.group(2);
            int col = colStr.charAt(0) - 'a';
            int row = Integer.parseInt(rowStr) - 1;
            if (col >= 0 && col < boardSize && row >= 0 && row < boardSize) {
                moves.add(new Point(col, row));
            } else {
                appendLog("忽略越界坐标: " + colStr + rowStr);
            }
        }
        return moves;
    }



    private void startMultiAnalysis() {
        stopMultiAnalysis();
        multiAnalysisActive = true;
        boardView.setMultiAnalysisMode(true);
        boardView.setMultiAnalysisComplete(false);
        boardView.setEnabled(true);
        appendLog("=== 多点分析开始 (YXNBEST 3) ===");

        final List<Point> historySnapshot = new ArrayList<Point>(moveHistory);
        final boolean isBlackTurn = historySnapshot.size() % 2 == 0;
        final boolean aiIsBlack = isBlackTurn;

        new Thread(new Runnable() {
                @Override
                public void run() {
                    RapfiEngine engine = RapfiEngine.getInstance();
                    engine.getMultiBestMoves(historySnapshot, aiIsBlack, 3, thinkTimeMs, MainActivity.this);
                }
            }).start();
    }

    private void stopMultiAnalysis() {
        if (multiAnalysisActive) {
            multiAnalysisActive = false;
            boardView.setMultiAnalysisComplete(false);
            boardView.clearCandidates();
            appendLog("多点分析已清除");
        }
    }

    public void onMultiAnalysisCandidate(final int rank, final int x, final int y,
                                         final int eval, final double winrate, final String depth) {
        handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!multiAnalysisActive) return;
                    BoardView.CandidatePoint cp = new BoardView.CandidatePoint(x, y, eval, winrate, depth);
                    boardView.updateCandidate(cp);
                }
            });
    }

    public void onMultiAnalysisComplete(final int count) {
        handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!multiAnalysisActive) return;
                    boardView.setMultiAnalysisComplete(true);
                    appendLog("多点分析完成，共 " + count + " 个候选点");
                    appendLog("点击棋盘上的彩色圆圈选择落子");
                }
            });
    }

    private void executeCandidateMove(int x, int y) {
        if (isReviewMode) {
            appendLog("打谱模式下不能落子");
            return;
        }
        if (analyzingReview.get()) {
            appendLog("正在分析中，禁止落子");
            return;
        }
        boardView.clearLostPoints();
        stopMultiAnalysis();
        if (GameLogic.isOccupied(moveHistory, x, y)) {
            appendLog("此处已有棋子");
            return;
        }
        moveHistory.add(new Point(x, y));
        updateBoard();
        playMoveSound();
        boardView.clearSelection();
        checkGameEnd();
        if (gameOver) return;
        if (!isHumanTurn()) {
            if (aiMoveRequested.compareAndSet(false, true)) {
                aiThinking = true;
                aiInterrupted = false;
                boardView.setEnabled(false);
                final List<Point> snapshot = new ArrayList<Point>(moveHistory);
                new Thread(new Runnable() {
                        @Override
                        public void run() {
                            triggerAIMove(snapshot);
                        }
                    }).start();
            }
        }
    }

    private String coordToString(int x, int y) {
        return "" + (char) ('A' + x) + (y + 1);
    }


    private void showNewGameDialog() {
        newGameDialogVisible = true;
        btnNewGame.setEnabled(false);

        LayoutInflater inflater = LayoutInflater.from(this);
        final View view = inflater.inflate(R.layout.dialog_new_game, null);


        final RadioGroup radioGroup = (RadioGroup) view.findViewById(R.id.radioGroup);
        final SeekBar seekTime = (SeekBar) view.findViewById(R.id.seekTime);
        final TextView tvTimeValue = (TextView) view.findViewById(R.id.tvTimeValue);
        final SeekBar seekCaution = (SeekBar) view.findViewById(R.id.seekCaution);
        final TextView tvCautionValue = (TextView) view.findViewById(R.id.tvCautionValue);
        final RadioGroup radioSearchType = (RadioGroup) view.findViewById(R.id.radioSearchType);

        if (isReviewMode) {
            ((RadioButton) view.findViewById(R.id.radioReview)).setChecked(true);
        } else {
            if (aiControlBlack[0]) {
                ((RadioButton) view.findViewById(R.id.radioWhite)).setChecked(true);
            } else {
                ((RadioButton) view.findViewById(R.id.radioBlack)).setChecked(true);
            }
        }

        int timeSec = Math.max(1, Math.min(60, thinkTimeMs / 1000));
        seekTime.setProgress(timeSec - 1);
        tvTimeValue.setText(timeSec + "秒");

        seekCaution.setProgress(cautionFactor - 1);
        tvCautionValue.setText(String.valueOf(cautionFactor));

        if ("mcts".equals(searchType)) {
            ((RadioButton) view.findViewById(R.id.radioMCTS)).setChecked(true);
        } else {
            ((RadioButton) view.findViewById(R.id.radioAlphaBeta)).setChecked(true);
        }

        seekTime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    tvTimeValue.setText((progress + 1) + "秒");
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

        seekCaution.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    tvCautionValue.setText(String.valueOf(progress + 1));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("开始", null)
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface d) {
                    Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    Button negBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

                    if (posBtn != null) {
                        posBtn.setBackgroundResource(R.drawable.ios_button_bg);
                        posBtn.setTextColor(Color.WHITE);
                        posBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {

                                    thinkTimeMs = (seekTime.getProgress() + 1) * 1000;
                                    cautionFactor = seekCaution.getProgress() + 1;
                                    int searchTypeId = radioSearchType.getCheckedRadioButtonId();
                                    searchType = (searchTypeId == R.id.radioMCTS) ? "mcts" : "alphabeta";


                                    if (engineReady) {
                                        syncAllConfigs();
                                    }

                                    int checkedId = radioGroup.getCheckedRadioButtonId();
                                    if (checkedId == R.id.radioReview) {
                                        if (!isReviewMode) enterReviewMode();
                                        else {
                                            exitReviewMode();
                                            enterReviewMode();
                                        }
                                        dialog.dismiss();
                                        return;
                                    }

                                    if (isReviewMode) exitReviewMode();
                                    if (checkedId == R.id.radioBlack) {
                                        aiControlBlack[0] = false;
                                        aiControlWhite[0] = true;
                                    } else {
                                        aiControlBlack[0] = true;
                                        aiControlWhite[0] = false;
                                    }
                                    dialog.dismiss();
                                    newGame();
                                }
                            });
                    }
                    if (negBtn != null) {
                        negBtn.setBackgroundResource(R.drawable.ios_button_bg);
                        negBtn.setTextColor(Color.WHITE);
                        negBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    dialog.dismiss();
                                }
                            });
                    }
                }
            });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    newGameDialogVisible = false;
                    if (engineReady && !engineBusy) {
                        btnNewGame.setEnabled(true);
                    }
                    if (!gameOver && !aiThinking && isHumanTurn() && !isReviewMode) {
                        boardView.setEnabled(true);
                    }
                }
            });

        dialog.show();
    }

    private void newGame() {
        moveHistory.clear();
        gameOver = false;
        aiThinking = false;
        aiInterrupted = false;
        aiMoveRequested.set(false);
        boardView.setWinLine(null);
        boardView.clearSelection();
        boardView.clearHint();
        boardView.clearLostPoints();
        stopMultiAnalysis();
        tvDepth.setText("搜索层数：--");
        tvEval.setText("胜率：--");
        tvStep.setText("0/0");
        updateBoard();

        new Thread(new Runnable() {
                @Override
                public void run() {
                    RapfiEngine engine = RapfiEngine.getInstance();
                    engine.restart();
                    engine.setCautionFactor(cautionFactor);
                    engine.setSearchType(searchType);
                    engine.setRule(currentRule);
                    engine.setTimeLimitMs(thinkTimeMs);
                    engine.updateTimeout();

                    handler.post(new Runnable() {
                            @Override
                            public void run() {
                                appendLog("新游戏，您执" + (aiControlBlack[0] ? "白" : "黑") +
                                          "，AI执" + (aiControlBlack[0] ? "黑" : "白") +
                                          "，规则：无禁手，思考时间：" + (thinkTimeMs / 1000) + "秒" +
                                          "，谨慎因子：" + cautionFactor +
                                          "，搜索类型：" + searchType);
                                engineBusy = false;
                                if (currentTurnIsAI()) {
                                    if (aiMoveRequested.compareAndSet(false, true)) {
                                        aiThinking = true;
                                        boardView.setEnabled(false);
                                        final List<Point> snapshot = new ArrayList<Point>(moveHistory);
                                        new Thread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    triggerAIMove(snapshot);
                                                }
                                            }).start();
                                    }
                                } else {
                                    boardView.setEnabled(true);
                                }
                            }
                        });
                }
            }).start();
    }

    private boolean currentTurnIsAI() {
        boolean isBlackTurn = moveHistory.size() % 2 == 0;
        return isBlackTurn ? aiControlBlack[0] : aiControlWhite[0];
    }

    private boolean isHumanTurn() {
        return !currentTurnIsAI();
    }

    private void triggerAIMove(final List<Point> snapshot) {
        if (isReviewMode) return;
        if (analyzingReview.get()) {
            handler.post(new Runnable() {
                    @Override
                    public void run() {
                        appendLog("正在分析中，AI暂不落子");
                    }
                });
            return;
        }
        if (!currentTurnIsAI()) {
            handler.post(new Runnable() {
                    @Override
                    public void run() {
                        aiThinking = false;
                        aiMoveRequested.set(false);
                        boardView.setEnabled(true);
                    }
                });
            return;
        }

        boolean isBlackTurn = snapshot.size() % 2 == 0;
        final boolean aiIsBlack = isBlackTurn;
        final int[] move = RapfiEngine.getInstance().getBestMove(snapshot, aiIsBlack);

        handler.post(new Runnable() {
                @Override
                public void run() {
                    if (move != null) {
                        if (GameLogic.isGameFinished(moveHistory)) {
                            gameOver = true;
                            aiMoveRequested.set(false);
                            boardView.setEnabled(false);
                            return;
                        }
                        if (GameLogic.isOccupied(moveHistory, move[0], move[1])) {
                            appendLog("AI尝试在已有棋子的位置落子，忽略");
                            aiMoveRequested.set(false);
                            boardView.setEnabled(true);
                            return;
                        }
                        boardView.clearLostPoints();
                        moveHistory.add(new Point(move[0], move[1]));
                        updateBoard();
                        boardView.clearSelection();
                        boardView.clearHint();
                        appendLog("AI落子(" + (aiIsBlack ? "黑" : "白") + "): " + move[0] + "," + move[1]);
                        playMoveSound();
                        checkGameEnd();
                        if (gameOver) {
                            aiMoveRequested.set(false);
                            return;
                        }
                        if (currentTurnIsAI() && !aiInterrupted) {
                            if (aiMoveRequested.compareAndSet(false, true)) {
                                aiThinking = true;
                                final List<Point> newSnapshot = new ArrayList<Point>(moveHistory);
                                new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            triggerAIMove(newSnapshot);
                                        }
                                    }).start();
                            }
                        } else {
                            aiThinking = false;
                            aiMoveRequested.set(false);
                            boardView.setEnabled(true);
                        }
                    } else {
                        if (aiInterrupted) {
                            appendLog("AI思考被中断，未返回有效着法");
                        } else {
                            appendLog("AI计算失败");
                        }
                        aiThinking = false;
                        aiMoveRequested.set(false);
                        boardView.setEnabled(true);
                    }
                }
            });
    }

    private void checkGameEnd() {
        if (moveHistory.isEmpty()) return;
        int lastColor = (moveHistory.size() - 1) % 2 == 0 ? GameLogic.BLACK : GameLogic.WHITE;
        String winner = null;
        if (GameLogic.isFiveInRow(moveHistory)) {
            winner = (lastColor == GameLogic.BLACK) ? "黑方" : "白方";
        } else if (moveHistory.size() == boardSize * boardSize) {
            winner = "平局";
        }
        if (winner != null) {
            gameOver = true;
            aiThinking = false;
            boardView.setEnabled(false);
            boardView.clearHint();
            boardView.clearSelection();
            boardView.clearLostPoints();
            stopMultiAnalysis();
            String msg = winner.equals("平局") ? "棋盘已满，平局！" : winner + "获胜！";
            appendLog(msg);
            if (!winner.equals("平局")) {
                int[][] line = GameLogic.getWinningLine(moveHistory);
                boardView.setWinLine(line);
            }
            updateBoard();
        }
    }

    private void updateBoard() {
        boardView.setMoveHistory(moveHistory, moveHistory.size() - 1);
    }

    private void appendLog(String msg) {
        tvLog.append(msg + "\n");
        int excess = tvLog.getLineCount() - MAX_LOG_LINES;
        if (excess > 0) {
            int end = tvLog.getLayout().getLineEnd(excess - 1);
            tvLog.getEditableText().delete(0, end);
        }
        scrollLog.post(new Runnable() {
                @Override
                public void run() {
                    scrollLog.fullScroll(View.FOCUS_DOWN);
                }
            });
    }

    private void appendLog(CharSequence text) {
        tvLog.append(text);
        int excess = tvLog.getLineCount() - MAX_LOG_LINES;
        if (excess > 0) {
            int end = tvLog.getLayout().getLineEnd(excess - 1);
            tvLog.getEditableText().delete(0, end);
        }
        scrollLog.post(new Runnable() {
                @Override
                public void run() {
                    scrollLog.fullScroll(View.FOCUS_DOWN);
                }
            });
    }


    private void initSound() {
        try {
            soundPool = new SoundPool(1, AudioManager.STREAM_MUSIC, 0);
            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                    @Override
                    public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                        if (status == 0) {
                            soundLoaded = true;
                            appendLog("音效加载成功");
                        } else {
                            appendLog("音效加载失败，状态码：" + status);
                        }
                    }
                });
            AssetFileDescriptor afd = getAssets().openFd("a.wav");
            soundId = soundPool.load(afd, 1);
        } catch (IOException e) {
            appendLog("音效加载异常: " + e.getMessage());
            soundLoaded = false;
        }
    }

    private void playMoveSound() {
        if (soundPool != null && soundLoaded) {
            soundPool.play(soundId, 1.0f, 1.0f, 0, 0, 1.0f);
        }
    }


    private void parseEngineMessage(String line) {
        if (line.startsWith("MESSAGE REALTIME LOST ")) {
            try {
                String coords = line.substring(22).trim();
                String[] parts = coords.split(",");
                if (parts.length == 2) {
                    final int x = Integer.parseInt(parts[0].trim());
                    final int y = Integer.parseInt(parts[1].trim());
                    if (x >= 0 && x < boardSize && y >= 0 && y < boardSize) {
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    boardView.addLostPoint(x, y);
                                }
                            });
                    }
                }
            } catch (Exception ignored) {}
            return;
        }

        if (line.startsWith("INFO WINRATE ")) {
            try {
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    double wr = Double.parseDouble(parts[2]);
                    final String winrateStr = String.format("胜率：%.2f%%", wr * 100);
                    handler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvEval.setText(winrateStr);
                            }
                        });
                }
            } catch (Exception ignored) {}
            return;
        }

        if (line.startsWith("INFO PV ")) {
            try {
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    currentPvIndex = Integer.parseInt(parts[2]);
                }
            } catch (Exception ignored) {}
            return;
        }

        if (line.startsWith("INFO EVAL ")) {
            try {
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    currentCandidateEval = Integer.parseInt(parts[2]);
                }
            } catch (Exception ignored) {}
            return;
        }

        if (line.startsWith("INFO BESTLINE ") && multiAnalysisActive) {
            try {
                String coords = line.substring(14).trim();
                String[] coordPairs = coords.split(" ");
                if (coordPairs.length > 0) {
                    String[] xy = coordPairs[0].split(",");
                    int x = Integer.parseInt(xy[0]);
                    int y = Integer.parseInt(xy[1]);
                    if (currentPvIndex >= 0) {
                        final int rank = currentPvIndex + 1;
                        final int fx = x;
                        final int fy = y;
                        final int feval = currentCandidateEval;
                        final double fwinrate = currentCandidateWinrate;
                        final String fdepth = currentCandidateDepth;
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!multiAnalysisActive) return;
                                    onMultiAnalysisCandidate(rank, fx, fy, feval, fwinrate, fdepth);
                                }
                            });
                    }
                }
            } catch (Exception ignored) {}
            return;
        }

        if (line.contains("MESSAGE Depth") && line.contains("Eval")) {
            try {
                if ("alphabeta".equals(searchType)) {
                    int depthStart = line.indexOf("Depth ") + 6;
                    if (depthStart >= 6) {
                        int depthEnd = line.indexOf(' ', depthStart);
                        if (depthEnd == -1) depthEnd = line.indexOf('|', depthStart);
                        if (depthEnd == -1) depthEnd = line.length();
                        String depthStr = line.substring(depthStart, depthEnd).trim();
                        if (!depthStr.isEmpty()) {
                            currentCandidateDepth = depthStr;
                            final String depth = depthStr;
                            handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        tvDepth.setText("搜索层数：" + depth);
                                    }
                                });
                        }
                    }
                }

                int evalIdx = line.indexOf("Eval ");
                if (evalIdx != -1) {
                    int start = evalIdx + 5;
                    int end = line.indexOf(' ', start);
                    if (end == -1) end = line.length();
                    final String evalStr = line.substring(start, end).trim();

                    if (evalStr.length() > 2 && evalStr.charAt(0) == '+' && evalStr.charAt(1) == 'M') {
                        SpannableString ss = new SpannableString("AI必胜！\n");
                        ss.setSpan(new ForegroundColorSpan(Color.RED), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        appendLog(ss);
                    } else if (evalStr.length() > 2 && evalStr.charAt(0) == '-' && evalStr.charAt(1) == 'M') {
                        SpannableString ss = new SpannableString("AI必输！\n");
                        ss.setSpan(new ForegroundColorSpan(Color.RED), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        appendLog(ss);
                    }

                    if (!multiAnalysisActive && (aiThinking || analyzingReview.get())) {
                        int lastPipe = line.lastIndexOf('|');
                        if (lastPipe != -1) {
                            String afterPipe = line.substring(lastPipe + 1).trim();
                            String[] tokens = afterPipe.split("\\s+");
                            if (tokens.length > 0 && tokens[0].length() >= 2) {
                                char colChar = tokens[0].charAt(0);
                                if (colChar >= 'A' && colChar <= 'A' + boardSize - 1) {
                                    final int x = colChar - 'A';
                                    final int y = Integer.parseInt(tokens[0].substring(1)) - 1;
                                    if (x >= 0 && x < boardSize && y >= 0 && y < boardSize) {
                                        boardView.setBestHint(x, y, evalStr);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (soundPool != null) soundPool.release();
        RapfiEngine.getInstance().stop();
    }


    public static class Point {
        public int x, y;
        public Point(int x, int y) { this.x = x; this.y = y; }
    }

    public static class GameLogic {
        public static final int BLACK = 0, WHITE = 1;
        private static int boardSize = 15;

        public static void setBoardSize(int size) { boardSize = size; }

        public static boolean isOccupied(List<Point> moves, int x, int y) {
            for (Point p : moves) {
                if (p.x == x && p.y == y) return true;
            }
            return false;
        }

        public static boolean isGameFinished(List<Point> moves) {
            return isFiveInRow(moves) || moves.size() == boardSize * boardSize;
        }

        public static boolean isFiveInRow(List<Point> moves) {
            if (moves.isEmpty()) return false;
            int lastX = moves.get(moves.size() - 1).x;
            int lastY = moves.get(moves.size() - 1).y;
            int color = (moves.size() - 1) % 2;
            return checkWin(moves, lastX, lastY, color);
        }

        private static boolean checkWin(List<Point> moves, int x, int y, int color) {
            int[][] dirs = {{1,0},{0,1},{1,1},{1,-1}};
            for (int[] d : dirs) {
                int count = 1;
                for (int step = 1; step <= 5; step++) {
                    int nx = x + d[0] * step, ny = y + d[1] * step;
                    if (!isValid(nx, ny)) break;
                    if (getColorAt(moves, nx, ny) == color) count++;
                    else break;
                }
                for (int step = 1; step <= 5; step++) {
                    int nx = x - d[0] * step, ny = y - d[1] * step;
                    if (!isValid(nx, ny)) break;
                    if (getColorAt(moves, nx, ny) == color) count++;
                    else break;
                }
                if (count >= 5) return true;
            }
            return false;
        }

        private static int getColorAt(List<Point> moves, int x, int y) {
            for (int i = 0; i < moves.size(); i++) {
                Point p = moves.get(i);
                if (p.x == x && p.y == y) return i % 2;
            }
            return -1;
        }

        private static boolean isValid(int x, int y) {
            return x >= 0 && x < boardSize && y >= 0 && y < boardSize;
        }

        public static int[][] getWinningLine(List<Point> moves) {
            if (moves.isEmpty()) return null;
            int lastX = moves.get(moves.size() - 1).x;
            int lastY = moves.get(moves.size() - 1).y;
            int color = (moves.size() - 1) % 2;
            int[][] dirs = {{1,0},{0,1},{1,1},{1,-1}};
            for (int[] d : dirs) {
                ArrayList<int[]> forward = new ArrayList<int[]>();
                ArrayList<int[]> backward = new ArrayList<int[]>();
                for (int step = 1; step <= 5; step++) {
                    int nx = lastX + d[0] * step, ny = lastY + d[1] * step;
                    if (!isValid(nx, ny) || getColorAt(moves, nx, ny) != color) break;
                    forward.add(new int[]{nx, ny});
                }
                for (int step = 1; step <= 5; step++) {
                    int nx = lastX - d[0] * step, ny = lastY - d[1] * step;
                    if (!isValid(nx, ny) || getColorAt(moves, nx, ny) != color) break;
                    backward.add(new int[]{nx, ny});
                }
                if (forward.size() + backward.size() + 1 >= 5) {
                    ArrayList<int[]> line = new ArrayList<int[]>();
                    for (int j = backward.size() - 1; j >= 0; j--) line.add(backward.get(j));
                    line.add(new int[]{lastX, lastY});
                    for (int j = 0; j < forward.size(); j++) line.add(forward.get(j));
                    return line.toArray(new int[line.size()][2]);
                }
            }
            return null;
        }
    }

    public static class RapfiEngine {
        private static final int IO_BUFFER_SIZE = 1024 * 1024;
        private static final long EXTRA_WAIT_MS = 12000L;

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
        private volatile int currentCautionFactor = 3;
        private volatile String currentSearchType = "alphabeta";
        private volatile int boardSize = 15;

        private RapfiEngine() {}

        public void setBoardSize(int size) { boardSize = size; }
        public void setThreads(int threads) { currentThreads = threads; }
        public void setTimeLimitMs(int ms) {
            this.currentTimeLimit = ms;
        }
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
}
