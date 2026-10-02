package com.yjbrly.fivesolver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private int cautionFactor = 0;   // 默认不谨慎，更强
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

    private static final Pattern SGF_PATTERN = Pattern.compile("([a-o])(\\d{1,2})", Pattern.CASE_INSENSITIVE);

    // ===== 首次启动 GPL 声明相关 =====
    private static final String PREF_NAME = "app_prefs";
    private static final String PREF_GPL_ACCEPTED = "gpl_accepted";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (hasAcceptedGpl()) {
            initEngineAndStart();
        } else {
            showGplDialog();
        }
    }

    // ==================== GPL 声明 ====================

    private boolean hasAcceptedGpl() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        return sp.getBoolean(PREF_GPL_ACCEPTED, false);
    }

    private void showGplDialog() {
        View boardV = findViewById(R.id.boardView);
        if (boardV != null) boardV.setEnabled(false);
        View newGameV = findViewById(R.id.btnNewGame);
        if (newGameV != null) newGameV.setEnabled(false);

        // 用自定义布局，避免 setMessage 长文本在某些设备上显示空白
        View contentView = LayoutInflater.from(this).inflate(R.layout.dialog_gpl, null);
        final TextView tvGplContent = (TextView) contentView.findViewById(R.id.tvGplContent);
        tvGplContent.setText(buildGplMessage());

        final AlertDialog dialog = new AlertDialog.Builder(this)
			.setTitle("开源许可声明")
			.setView(contentView)
			.setCancelable(false)
			.setPositiveButton("我同意", null)
			.setNegativeButton("退出", null)
			.create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
				@Override
				public void onShow(DialogInterface d) {
					// 让滚动条一开始就归到顶部
					ScrollView sv = (ScrollView) ((View) tvGplContent.getParent());
					if (sv != null) sv.scrollTo(0, 0);

					Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
					Button negBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

					if (posBtn != null) {
						posBtn.setBackgroundResource(R.drawable.ios_button_bg);
						posBtn.setTextColor(Color.WHITE);
						posBtn.setOnClickListener(new View.OnClickListener() {
								@Override
								public void onClick(View v) {
									getSharedPreferences(PREF_NAME, MODE_PRIVATE)
										.edit()
										.putBoolean(PREF_GPL_ACCEPTED, true)
										.apply();
									dialog.dismiss();
									initEngineAndStart();
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
									finish();
								}
							});
					}
				}
			});

        dialog.show();
    }

    private String buildGplMessage() {
        StringBuilder sb = new StringBuilder();

        sb.append("本应用内嵌了以下开源组件：\n\n");
        sb.append("────────────────────────\n");
        sb.append("【Rapfi 五子棋引擎】\n");
        sb.append("────────────────────────\n");
        sb.append("作者：dhbloo 及贡献者\n");
        sb.append("项目主页：\nhttps://github.com/dhbloo/rapfi\n\n");
        sb.append("许可协议：\nGNU General Public License v3 (GPL v3)\n\n");
        sb.append("Rapfi 是自由软件，您可以在 GPL v3 条款下自由使用、");
        sb.append("修改和分发。任何分发行为必须附带完整源码，");
        sb.append("修改后的代码也必须以 GPL v3 协议开源。\n\n");

        sb.append("────────────────────────\n");
        sb.append("【本应用】\n");
        sb.append("开源地址：\nhttps://github.com/3453755574/-Solver\n");
        sb.append("────────────────────────\n\n");
        sb.append("All Rights Reserved.\n");
        sb.append("保留所有权利。\n\n");
        sb.append("已开源 ");
        sb.append("点击「我同意」表示您已阅读并接受上述条款。\n\n");

        // ============ 附加 assets/Copying.txt 完整许可证文本 ============
        String copying = readCopyingFromAssets();
        if (copying != null && !copying.trim().isEmpty()) {
            sb.append("\n\n────────────────────────\n");
            sb.append("【GPL v3 完整许可证文本】\n");
            sb.append("(来自 assets/Copying.txt)\n");
            sb.append("────────────────────────\n\n");
            sb.append(copying);
        } else {
            sb.append("\n\n(提示：未找到 assets/Copying.txt，");
            sb.append("完整许可证请见 https://www.gnu.org/licenses/gpl-3.0.txt)");
        }

        return sb.toString();
    }

    private String readCopyingFromAssets() {
        InputStream is = null;
        BufferedReader br = null;
        try {
            is = getAssets().open("Copying.txt");
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        } finally {
            try { if (br != null) br.close(); } catch (IOException ignored) {}
            try { if (is != null) is.close(); } catch (IOException ignored) {}
        }
    }

    // ==================== 引擎初始化 ====================

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
        appendLog("配置已强制同步：思考时间=" + (thinkTimeMs / 1000) + "秒，谨慎因子=" + cautionFactor +
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
									appendLog("分析未返回有效着法（超时 " + (thinkTimeMs / 1000) + " 秒，实际耗时 " + (elapsed / 1000) + " 秒）");
								} else {
									appendLog("分析完成，最佳着法：" + bestMove[0] + "," + bestMove[1] + "，耗时 " + (elapsed / 1000) + " 秒");
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

        // 谨慎因子允许 0 起始
        seekCaution.setProgress(Math.max(0, cautionFactor));
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

				@Override public void onStartTrackingTouch(SeekBar seekBar) {}
				@Override public void onStopTrackingTouch(SeekBar seekBar) {}
			});

        seekCaution.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					tvCautionValue.setText(String.valueOf(progress));
				}

				@Override public void onStartTrackingTouch(SeekBar seekBar) {}
				@Override public void onStopTrackingTouch(SeekBar seekBar) {}
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
									cautionFactor = seekCaution.getProgress();
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
}
